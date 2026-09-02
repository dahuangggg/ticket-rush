import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const eventId = __ENV.EVENT_ID;
const vus = Number(__ENV.VUS || 100);
const duration = __ENV.DURATION || '30s';

export const options = {
  vus,
  duration,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
};

const successfulResponses = new Counter('event_detail_successes');
const failedResponses = new Counter('event_detail_failures');
const eventDetailDuration = new Trend('event_detail_duration', true);

export default function () {
  const response = http.get(`${__ENV.BASE_URL}/api/events/${eventId}`, {
    tags: { operation: 'event-detail' },
  });

  let valid = false;
  try {
    // The benchmark uses 18-digit IDs, which JavaScript Number cannot represent exactly.
    valid = response.status === 200
      && (response.body.includes(`"id":"${eventId}"`)
        || response.body.includes(`"id":${eventId}`));
  } catch (_) {
    valid = false;
  }

  if (valid) successfulResponses.add(1);
  else failedResponses.add(1);
  eventDetailDuration.add(response.timings.duration);
  check(response, { 'event detail returned expected fixture': () => valid });
}

export function handleSummary(data) {
  const durationValues = data.metrics.event_detail_duration?.values || {};
  const report = {
    virtualUsers: vus,
    configuredDuration: duration,
    requests: data.metrics.http_reqs?.values.count || 0,
    requestsPerSecond: data.metrics.http_reqs?.values.rate || 0,
    successfulResponses: data.metrics.event_detail_successes?.values.count || 0,
    failedResponses: data.metrics.event_detail_failures?.values.count || 0,
    httpFailureRate: data.metrics.http_req_failed?.values.rate || 0,
    averageMs: durationValues.avg ?? null,
    p50Ms: durationValues.med ?? null,
    p90Ms: durationValues['p(90)'] ?? null,
    p95Ms: durationValues['p(95)'] ?? null,
    p99Ms: durationValues['p(99)'] ?? null,
    maxMs: durationValues.max ?? null,
  };
  const json = `${JSON.stringify(report, null, 2)}\n`;
  const outputs = { stdout: json };
  if (__ENV.SUMMARY_FILE) outputs[__ENV.SUMMARY_FILE] = json;
  return outputs;
}
