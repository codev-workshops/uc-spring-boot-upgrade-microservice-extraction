// Shared k6 helpers: base URL, auth, and data seeding. No scenario logic here.
import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const VUS = parseInt(__ENV.VUS || '10', 10);
export const DURATION = __ENV.DURATION || '60s';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function authHeaders(token, extra = {}) {
  return { headers: { ...JSON_HEADERS, Authorization: `Token ${token}`, ...extra } };
}

function registerUser(user) {
  const res = http.post(
    `${BASE_URL}/users`,
    JSON.stringify({ user }),
    { headers: JSON_HEADERS, tags: { name: 'setup:register' } },
  );
  // 422 means the user already exists (e.g. re-running against a live DB) - that's fine.
  check(res, { 'setup: register 201/422': (r) => r.status === 201 || r.status === 422 });
  return res;
}

function login(email, password) {
  const res = http.post(
    `${BASE_URL}/users/login`,
    JSON.stringify({ user: { email, password } }),
    { headers: JSON_HEADERS, tags: { name: 'setup:login' } },
  );
  check(res, { 'setup: login 200': (r) => r.status === 200 });
  if (res.status !== 200) {
    throw new Error(`setup: login failed for ${email}: ${res.status} ${res.body}`);
  }
  return res.json('user.token');
}

function createArticle(token, i, tags) {
  const res = http.post(
    `${BASE_URL}/articles`,
    JSON.stringify({
      article: {
        title: `k6 seed article ${i} ${Date.now()}`,
        description: `Seed article ${i} for load testing`,
        body: `Lorem ipsum body for seed article ${i}. `.repeat(5),
        tagList: tags,
      },
    }),
    { ...authHeaders(token), tags: { name: 'setup:createArticle' } },
  );
  check(res, { 'setup: create article 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json('article.slug') : null;
}

function addComment(token, slug, i) {
  const res = http.post(
    `${BASE_URL}/articles/${slug}/comments`,
    JSON.stringify({ comment: { body: `k6 seed comment ${i} on ${slug}` } }),
    { ...authHeaders(token), tags: { name: 'setup:addComment' } },
  );
  check(res, { 'setup: add comment 201': (r) => r.status === 201 });
}

/**
 * Registers/logs in a primary user and a secondary "author" user, seeds articles
 * (with tags) and comments, and returns everything the scenarios need.
 *
 * Options: { articles: number, commentsPerArticle: number }
 */
export function seed({ articles = 12, commentsPerArticle = 3 } = {}) {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const password = 'k6-password-123';

  const primary = { username: `k6user-${suffix}`, email: `k6user-${suffix}@example.com`, password };
  const author = { username: `k6author-${suffix}`, email: `k6author-${suffix}@example.com`, password };

  registerUser(primary);
  registerUser(author);
  const token = login(primary.email, primary.password);
  const authorToken = login(author.email, author.password);

  const tags = ['k6', 'loadtest', 'graphql', 'rest'];
  const slugs = [];
  for (let i = 0; i < articles; i++) {
    // Alternate authors so author= / authoredBy filters return data for both users.
    const t = i % 2 === 0 ? token : authorToken;
    const slug = createArticle(t, i, [tags[i % tags.length], 'k6']);
    if (!slug) continue;
    slugs.push(slug);
    for (let c = 0; c < commentsPerArticle; c++) {
      addComment(c % 2 === 0 ? authorToken : token, slug, c);
    }
  }

  // Favorite a few articles and follow the author so favorited=/feed have data.
  slugs.slice(0, Math.ceil(slugs.length / 2)).forEach((slug) => {
    http.post(`${BASE_URL}/articles/${slug}/favorite`, null, {
      ...authHeaders(token),
      tags: { name: 'setup:favorite' },
    });
  });
  http.post(`${BASE_URL}/profiles/${author.username}/follow`, null, {
    ...authHeaders(token),
    tags: { name: 'setup:follow' },
  });

  if (slugs.length === 0) {
    throw new Error('setup: no articles were seeded - is the app running at ' + BASE_URL + '?');
  }

  return {
    baseUrl: BASE_URL,
    token,
    authorToken,
    username: primary.username,
    authorUsername: author.username,
    slugs,
    tags,
  };
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
