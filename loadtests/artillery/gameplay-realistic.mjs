import "dotenv/config";

export function loadState(requestParams, context, events, done) {
  const token = process.env.LOADTEST_TOKEN2 || "";

  requestParams.headers = {
    ...(requestParams.headers || {}),
    Authorization: `Bearer ${token}`
  };

  done();
}
