// k6 load test for the REST surface. Run: k6 run load-tests/rest-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { htmlReport } from 'https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import { BASE_URL, VUS, DURATION, seed, authHeaders, pick } from './lib/setup.js';

const ENDPOINTS = [
  'GET /articles',
  'GET /articles?tag',
  'GET /articles?author',
  'GET /articles?favorited',
  'GET /articles/{slug}',
  'GET /articles/{slug}/comments',
  'GET /profiles/{username}',
  'GET /tags',
  'GET /articles/feed',
  'POST /articles',
  'POST /articles/{slug}/comments',
  'POST /articles/{slug}/favorite',
  'POST /profiles/{username}/follow',
];

// k6 only reports per-tag sub-metrics in the summary when a threshold references them.
const perEndpointThresholds = {};
for (const e of ENDPOINTS) {
  perEndpointThresholds[`rest_endpoint_latency{endpoint:${e}}`] = ['p(95)<2000'];
  perEndpointThresholds[`rest_endpoint_requests{endpoint:${e}}`] = ['count>=0'];
}

export const options = {
  scenarios: {
    rest: {
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
    'http_req_duration{group:read}': ['p(95)<1500'],
    ...perEndpointThresholds,
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const endpointLatency = new Trend('rest_endpoint_latency', true);
const endpointRequests = new Counter('rest_endpoint_requests');
const endpointErrors = new Rate('rest_endpoint_errors');

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

export function setup() {
  return seed({ articles: 12, commentsPerArticle: 3 });
}

function track(endpoint, res, expected = [200]) {
  const ok = expected.includes(res.status);
  endpointLatency.add(res.timings.duration, { endpoint });
  endpointRequests.add(1, { endpoint });
  endpointErrors.add(!ok, { endpoint });
  check(res, { [`${endpoint} -> ${expected.join('/')}`]: () => ok });
  return ok;
}

function get(endpoint, url, params = {}) {
  const res = http.get(url, { ...params, tags: { name: endpoint, group: 'read', ...(params.tags || {}) } });
  track(endpoint, res);
  return res;
}

function listArticles(data) {
  const variant = Math.random();
  const offset = pick([0, 5, 10]);
  const limit = pick([5, 10, 20]);
  let qs = `offset=${offset}&limit=${limit}`;
  let endpoint = 'GET /articles';
  if (variant < 0.25) {
    qs += `&tag=${pick(data.tags)}`;
    endpoint = 'GET /articles?tag';
  } else if (variant < 0.45) {
    qs += `&author=${pick([data.username, data.authorUsername])}`;
    endpoint = 'GET /articles?author';
  } else if (variant < 0.6) {
    qs += `&favorited=${data.username}`;
    endpoint = 'GET /articles?favorited';
  }
  get(endpoint, `${data.baseUrl}/articles?${qs}`);
}

const writes = {
  createArticle(data) {
    const res = http.post(
      `${data.baseUrl}/articles`,
      JSON.stringify({
        article: {
          title: `k6 vu${__VU} iter${__ITER} ${Date.now()}`,
          description: 'created during load test',
          body: 'load test body',
          tagList: ['k6'],
        },
      }),
      { ...authHeaders(data.token), tags: { name: 'POST /articles', group: 'write' } },
    );
    track('POST /articles', res, [200, 201]);
  },
  addComment(data) {
    const slug = pick(data.slugs);
    const res = http.post(
      `${data.baseUrl}/articles/${slug}/comments`,
      JSON.stringify({ comment: { body: `k6 comment vu${__VU} iter${__ITER}` } }),
      { ...authHeaders(data.token), tags: { name: 'POST /articles/{slug}/comments', group: 'write' } },
    );
    track('POST /articles/{slug}/comments', res, [201]);
  },
  favorite(data) {
    const slug = pick(data.slugs);
    const res = http.post(`${data.baseUrl}/articles/${slug}/favorite`, null, {
      ...authHeaders(data.token),
      tags: { name: 'POST /articles/{slug}/favorite', group: 'write' },
    });
    track('POST /articles/{slug}/favorite', res, [200]);
  },
  follow(data) {
    const res = http.post(`${data.baseUrl}/profiles/${data.authorUsername}/follow`, null, {
      ...authHeaders(data.token),
      tags: { name: 'POST /profiles/{username}/follow', group: 'write' },
    });
    track('POST /profiles/{username}/follow', res, [200]);
  },
};

export default function (data) {
  const r = Math.random();
  if (r < 0.4) {
    listArticles(data);
  } else if (r < 0.55) {
    get('GET /articles/{slug}', `${data.baseUrl}/articles/${pick(data.slugs)}`);
  } else if (r < 0.68) {
    get('GET /articles/{slug}/comments', `${data.baseUrl}/articles/${pick(data.slugs)}/comments`);
  } else if (r < 0.78) {
    get('GET /profiles/{username}', `${data.baseUrl}/profiles/${pick([data.username, data.authorUsername])}`);
  } else if (r < 0.85) {
    get('GET /tags', `${data.baseUrl}/tags`);
  } else if (r < 0.93) {
    get('GET /articles/feed', `${data.baseUrl}/articles/feed?offset=0&limit=${pick([5, 10, 20])}`, authHeaders(data.token));
  } else {
    // ~7% writes
    pick([writes.createArticle, writes.addComment, writes.favorite, writes.follow])(data);
  }
  sleep(Math.random() * 0.5 + 0.2);
}

export function handleSummary(data) {
  return {
    'load-tests/reports/rest-summary.json': JSON.stringify(data, null, 2),
    'load-tests/reports/rest-report.html': htmlReport(data, { title: `REST load test (${BASE_URL})` }),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
