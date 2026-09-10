import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    game_api: {
      executor: "constant-vus",
      vus: 10,
      duration: "30s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
  },
};

export default function () {
  const base = __ENV.API_BASE_URL || "http://localhost:3000";
  const token = __ENV.TEST_JWT || "";
  const params = { headers: { Authorization: `Bearer ${token}` } };
  const res = http.get(`${base}/api/health`, params);
  check(res, { "health responds": r => r.status === 200 });
  sleep(1);
}
