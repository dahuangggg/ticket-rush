import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const tokens = JSON.parse(open(__ENV.TOKENS_FILE));
const vus = Math.min(Number(__ENV.VUS || 100), tokens.length);

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    reservation_burst: {
      executor: 'shared-iterations',
      vus,
      iterations: tokens.length,
      maxDuration: '2m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    http_reqs: [`count==${tokens.length}`],
    iterations: [`count==${tokens.length}`],
    dropped_iterations: ['count==0'],
    rush_accepted: [`count==${tokens.length}`],
    rush_rejected: ['count==0'],
    rush_responses_accounted: [`count==${tokens.length}`],
  },
};

const accepted = new Counter('rush_accepted');
const rejected = new Counter('rush_rejected');
const responsesAccounted = new Counter('rush_responses_accounted');
const rushDuration = new Trend('rush_http_duration', true);

export default function () {
  const token = tokens[exec.scenario.iterationInTest];
  // Keep 18-digit Java Long IDs as decimal text; JavaScript Number would round them.
  const requestBody = `{"eventId":${__ENV.EVENT_ID},"skuId":${__ENV.SKU_ID},"quantity":1}`;
  const response = http.post(
    `${__ENV.BASE_URL}/api/ticket-rush/requests`,
    requestBody,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': `bench-${__ENV.RUN_ID}-${exec.scenario.iterationInTest}`,
        'Content-Type': 'application/json',
      },
      tags: { operation: 'ticket-rush' },
    },
  );

  let acceptedReservation = false;
  try {
    acceptedReservation = response.status === 202
      && response.json('status') === 'RESERVED'
      && Boolean(response.json('reservationId'));
  } catch (_) {
    acceptedReservation = false;
  }
  if (acceptedReservation) accepted.add(1);
  else rejected.add(1);
  responsesAccounted.add(1);
  rushDuration.add(response.timings.duration);
  check(response, {
    'reservation accepted': () => acceptedReservation,
  });
}

export function handleSummary(data) {
  const acceptedCount = data.metrics.rush_accepted?.values.count || 0;
  const rejectedCount = data.metrics.rush_rejected?.values.count || 0;
  const iterations = data.metrics.iterations?.values.count || 0;
  const droppedIterations = data.metrics.dropped_iterations?.values.count || 0;
  const httpRequests = data.metrics.http_reqs?.values.count || 0;
  const httpDuration = data.metrics.rush_http_duration?.values || {};
  const report = {
    requestedIterations: tokens.length,
    executedIterations: iterations,
    droppedIterations,
    httpRequests,
    accepted: acceptedCount,
    rejected: rejectedCount,
    accountedResponses: acceptedCount + rejectedCount,
    responsesConserved: acceptedCount + rejectedCount === tokens.length,
    iterationRatePerSecond: data.metrics.iterations?.values.rate || null,
    httpRequestsPerSecond: data.metrics.http_reqs?.values.rate || null,
    testRunDurationMs: data.state?.testRunDurationMs || null,
    httpAverageMs: httpDuration.avg ?? null,
    httpP50Ms: httpDuration.med ?? httpDuration['p(50)'] ?? null,
    httpP90Ms: httpDuration['p(90)'] ?? null,
    httpP95Ms: httpDuration['p(95)'] ?? null,
    httpP99Ms: httpDuration['p(99)'] ?? null,
    httpMaxMs: httpDuration.max ?? null,
  };
  const serialized = `${JSON.stringify(report, null, 2)}\n`;
  return {
    stdout: serialized,
    [__ENV.SUMMARY_FILE]: serialized,
  };
}
