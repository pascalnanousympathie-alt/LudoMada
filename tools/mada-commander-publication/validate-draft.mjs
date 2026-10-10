import fs from "node:fs/promises";
import path from "node:path";

const file = path.join(path.dirname(new URL(import.meta.url).pathname), "listing-metadata.draft.json");
const m = JSON.parse(await fs.readFile(file, "utf8"));
const i = m.extensions?.["com.openai"]?.interface;
const review = m.extensions?.["com.openai"]?.review;
const publication = m.extensions?.["com.openai"]?.publication;
const issues = [];
const requiredText = ["displayName", "shortDescription", "longDescription", "developerName", "category", "websiteURL", "supportURL", "privacyPolicyURL", "termsOfServiceURL"];
if (!i) issues.push("extensions.com.openai.interface is missing");
for (const key of requiredText) if (typeof i?.[key] !== "string" || !i[key].trim()) issues.push("missing listing field: " + key);
if ((i?.displayName || "").length > 30) issues.push("displayName exceeds 30 characters");
if ((i?.shortDescription || "").length > 30) issues.push("shortDescription exceeds 30 characters");
if ((i?.longDescription || "").length > 4000) issues.push("longDescription exceeds 4000 characters");
if ((i?.developerName || "").length > 80) issues.push("developerName exceeds 80 characters");
if (!Array.isArray(i?.defaultPrompt) || i.defaultPrompt.length > 3 || i.defaultPrompt.some(x => typeof x !== "string" || x.length > 128)) issues.push("defaultPrompt must contain at most 3 strings of 128 characters each");
if (!Array.isArray(i?.capabilities) || i.capabilities.some(x => typeof x !== "string" || !x.trim() || x.length > 120) || (i?.capabilities || []).length > 20) issues.push("capabilities list is invalid");
if ((review?.test_cases?.positive || []).length !== 5) issues.push("initial MCP review requires exactly five positive cases");
if ((review?.test_cases?.negative || []).length !== 3) issues.push("initial MCP review requires exactly three negative cases");
for (const test of review?.test_cases?.positive || []) for (const key of ["description","prompt","tools_triggered","expected_behavior"]) if (!test[key]) issues.push("positive case missing " + key + ": " + (test.description || "unnamed"));
const frSubtitle = publication?.translations?.["fr-FR"]?.subtitle || "";
const mgSubtitle = publication?.translations?.["mg-MG"]?.subtitle || "";
if (frSubtitle.length > 30) issues.push("fr-FR subtitle exceeds 30 characters");
if (mgSubtitle.length > 30) issues.push("mg-MG subtitle exceeds 30 characters");
const pending = [];
for (const [key,value] of Object.entries({
  developerName:i?.developerName,
  websiteURL:i?.websiteURL,
  supportURL:i?.supportURL,
  privacyPolicyURL:i?.privacyPolicyURL,
  termsOfServiceURL:i?.termsOfServiceURL,
  demo_recording_url:review?.demo_recording_url
})) {
  if (!value || /REPLACE_|TODO|EXAMPLE/i.test(value)) pending.push(key);
  else if (key.endsWith("URL") || key==="demo_recording_url") {
    try { if (new URL(value).protocol!=="https:") pending.push(key + " (must be HTTPS"); }
    catch { pending.push(key + " (invalid URL)"); }
  }
}
const result = {
  draftParsed:true,
  name:m.name,
  displayName:i?.displayName,
  positiveCases:(review?.test_cases?.positive || []).length,
  negativeCases:(review?.test_cases?.negative || []).length,
  structuralIssues:issues,
  publicationBlockers:[...new Set(pending.concat([
    "production public MCP is deployed but not ready: health remains HTTP 503 until dedicated database/schema, Google OAuth, and portal challenge are configured",
    "dedicated production database and Google OAuth credentials are not configured",
    "per-user OAuth and workspace isolation have not been end-to-end tested with real accounts",
    "the portal-issued challenge token and OpenAI domain verification are not configured or verified",
    "the production tool scan is not complete",
    "per-tool, per-hint annotation justifications are documented but still must be entered and verified in the OpenAI submission portal",
    "review video and positive/negative cases have not been run against a public test instance",
    "publisher identity has not been verified in OpenAI Platform"
  ]))]
};
console.log(JSON.stringify(result,null,2));
if (issues.length) process.exitCode = 1;
if (process.argv.includes("--require-ready") && result.publicationBlockers.length) process.exitCode = 2;
