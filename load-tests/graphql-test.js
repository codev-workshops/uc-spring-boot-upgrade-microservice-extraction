// k6 load test for the GraphQL surface (/graphql). Run: k6 run load-tests/graphql-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { htmlReport } from 'https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import { BASE_URL, VUS, DURATION, seed, pick } from './lib/setup.js';

const OPERATIONS = ['articles', 'articlesWithTag', 'articlesByAuthor', 'feed', 'profile', 'article', 'tags', 'me'];
const PAGED_OPERATIONS = ['articles', 'articlesWithTag', 'articlesByAuthor', 'feed', 'profile'];
// Page sizes to vary N+1 fan-out (each node triggers author/comments/Comment.author resolvers).
const PAGE_SIZES = [5, 10, 20];

// k6 only reports per-tag sub-metrics in the summary when a threshold references them.
const perOperationThresholds = {};
for (const op of OPERATIONS) {
  perOperationThresholds[`graphql_operation_latency{operation:${op}}`] = ['p(95)<2000'];
  perOperationThresholds[`graphql_operation_requests{operation:${op}}`] = ['count>=0'];
}
for (const op of PAGED_OPERATIONS) {
  for (const n of PAGE_SIZES) {
    perOperationThresholds[`graphql_operation_latency{operation:${op},page_size:${n}}`] = ['p(95)<2000'];
  }
}

export const options = {
  scenarios: {
    graphql: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '10s', target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    graphql_errors: ['rate<0.05'],
    ...perOperationThresholds,
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const opLatency = new Trend('graphql_operation_latency', true);
const opRequests = new Counter('graphql_operation_requests');
const opErrors = new Rate('graphql_errors');

// Nested article selection that triggers Article.author, Article.comments and Comment.author per node.
const ARTICLE_NODE = `
  slug title favorited favoritesCount tagList
  author { username following }
  comments(first: 10) { edges { node { id body author { username } } } }
`;

const QUERIES = {
  articles: (first) => `query { articles(first: ${first}) { edges { node { ${ARTICLE_NODE} } } pageInfo { hasNextPage endCursor } } }`,
  articlesWithTag: (first, tag) => `query { articles(first: ${first}, withTag: "${tag}") { edges { node { ${ARTICLE_NODE} } } } }`,
  articlesByAuthor: (first, u) => `query { articles(first: ${first}, authoredBy: "${u}") { edges { node { ${ARTICLE_NODE} } } } }`,
  feed: (first) => `query { feed(first: ${first}) { edges { node { ${ARTICLE_NODE} } } pageInfo { hasNextPage } } }`,
  profile: (username, first) => `query {
    profile(username: "${username}") { profile {
      username bio following
      articles(first: ${first}) { edges { node { slug title author { username } favoritesCount } } }
      favorites(first: ${first}) { edges { node { slug title author { username } } } }
      feed(first: ${first}) { edges { node { slug title author { username } } } }
    } } }`,
  article: (slug) => `query { article(slug: "${slug}") { ${ARTICLE_NODE} body } }`,
  tags: () => `query { tags }`,
  me: () => `query { me { username email profile { username bio following } } }`,
};

export function setup() {
  return seed({ articles: 20, commentsPerArticle: 3 });
}

function gql(data, operation, query, { auth = false, pageSize = 0 } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth) headers.Authorization = `Token ${data.token}`;
  const tags = { name: `graphql:${operation}`, operation, page_size: String(pageSize) };
  const res = http.post(`${data.baseUrl}/graphql`, JSON.stringify({ query }), { headers, tags });

  let body = null;
  try {
    body = res.json();
  } catch (e) {
    body = null;
  }
  const ok = res.status === 200 && body !== null && !body.errors && body.data !== undefined;

  opLatency.add(res.timings.duration, { operation, page_size: String(pageSize) });
  opRequests.add(1, { operation, page_size: String(pageSize) });
  opErrors.add(!ok, { operation });
  check(res, {
    [`${operation}: HTTP 200`]: (r) => r.status === 200,
    [`${operation}: no GraphQL errors`]: () => ok,
  });
  return res;
}

export default function (data) {
  const r = Math.random();
  const first = pick(PAGE_SIZES);
  if (r < 0.3) {
    gql(data, 'articles', QUERIES.articles(first), { pageSize: first });
  } else if (r < 0.4) {
    gql(data, 'articlesWithTag', QUERIES.articlesWithTag(first, pick(data.tags)), { pageSize: first });
  } else if (r < 0.48) {
    gql(data, 'articlesByAuthor', QUERIES.articlesByAuthor(first, pick([data.username, data.authorUsername])), { pageSize: first });
  } else if (r < 0.63) {
    gql(data, 'feed', QUERIES.feed(first), { auth: true, pageSize: first });
  } else if (r < 0.75) {
    gql(data, 'profile', QUERIES.profile(pick([data.username, data.authorUsername]), first), { auth: true, pageSize: first });
  } else if (r < 0.87) {
    gql(data, 'article', QUERIES.article(pick(data.slugs)));
  } else if (r < 0.94) {
    gql(data, 'tags', QUERIES.tags());
  } else {
    gql(data, 'me', QUERIES.me(), { auth: true });
  }
  sleep(Math.random() * 0.5 + 0.2);
}

export function handleSummary(data) {
  return {
    'load-tests/reports/graphql-summary.json': JSON.stringify(data, null, 2),
    'load-tests/reports/graphql-report.html': htmlReport(data, { title: `GraphQL load test (${BASE_URL})` }),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
