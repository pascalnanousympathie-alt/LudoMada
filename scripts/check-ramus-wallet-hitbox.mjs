#!/usr/bin/env node

const W = 720;
const H = 1616;

// Same transformed Wallet accessibility target used by PremiumHeroViewV2
const left = (325 / 720) * W;
const top = (70 / 1280) * H;
const width = ((420 - 325) / 720) * W;
const height = ((320 - 70) / 1280) * H;
const x = left + width / 2;
const y = top + height / 2;

const nx = x / W;
const ny = y / H;

// CCW transform used by HomeView.onTouchEvent
const lx = ny * 1280;
const ly = (1 - nx) * 720;

const walletHit = ly >= 325 && ly < 420 && lx < 310;

const report = {
  viewport: [W, H],
  accessibilityTarget: { left, top, width, height, center: [x, y] },
  transformedLandscapeCenter: [lx, ly],
  walletHit,
  expected: true
};

console.log(JSON.stringify(report, null, 2));

if (!walletHit) {
  console.error("RAMUS_WALLET_HITBOX_FAIL");
  process.exit(2);
}
console.log("RAMUS_WALLET_HITBOX_PASS");
