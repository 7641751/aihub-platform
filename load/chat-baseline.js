// M6 T2: baseline load against the gateway's OpenAI-compatible chat path.
//
// Run (k6 in Docker, host gateway):
//   docker run --rm -i -v "%CD%/load:/load:ro" -e BASE=http://host.docker.internal:8080 \
//     -e API_KEY=... -e VUS=50 -e DURATION=2m docker.m.daocloud.io/grafana/k6 run /load/chat-baseline.js
// Streamed TTFT run: add -e STREAM=1 (and lower VUS: streaming holds a connection per VU).
//
// What is measured, and the exact definition of each number:
//   * QPS            -> http_reqs rate (k6 prints it in the summary)
//   * P95 / P99      -> http_req_duration percentiles
//   * TTFT           -> http_req_waiting (time to FIRST byte). For an SSE response the first
//                       byte IS the first chunk, so this is the client-side first-token delay.
//                       It is NOT "the upstream's first token" -- the upstream's own delay is
//                       whatever the stub (or the real model) was configured with.
//   * rate_limited   -> our own counter for HTTP 429, so "rate limit on/off" (T3) compares a
//                       number that is collected the same way in both runs.
//
// Honest scope: the upstream is the local stub by default (M6 decision), so these numbers
// describe OUR gateway's own overhead. A small real-upstream run is a separate, clearly
// labelled comparison -- never mixed into the same table.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://host.docker.internal:8080';
const API_KEY = __ENV.API_KEY || '';
const MODEL = __ENV.MODEL || 'stub-chat';
const STREAM = (__ENV.STREAM || '0') === '1';
const VUS = Number(__ENV.VUS || (STREAM ? 20 : 50));
const DURATION = __ENV.DURATION || '2m';

const rateLimited = new Counter('rate_limited');
const ttft = new Trend('ttft_ms', true);

export const options = {
  scenarios: {
    chat: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  // Thresholds are intentionally LOOSE: the baseline's job is to *record* the numbers, not to
  // pass an invented SLO. Tighten them only after a real baseline exists (and say so in the report).
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{expected_response:true}': ['p(95)<2000', 'p(99)<5000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const body = JSON.stringify({
    model: MODEL,
    stream: STREAM,
    messages: [{ role: 'user', content: 'ping from k6' }],
  });

  const res = http.post(BASE + '/v1/chat/completions', body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + API_KEY,
    },
    tags: { stream: STREAM ? 'true' : 'false' },
  });

  ttft.add(res.timings.waiting);

  if (res.status === 429) {
    rateLimited.add(1);
  }

  check(res, {
    'status is 200': (r) => r.status === 200,
    'body looks like a completion': (r) =>
      STREAM ? String(r.body).indexOf('data:') >= 0 : String(r.body).indexOf('"choices"') >= 0,
  });
}
