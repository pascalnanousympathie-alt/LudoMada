#!/usr/bin/env node

const W = 720;
const H = 1616;

// Same portrait Wallet accessibility target used by PremiumHeroViewV2 (e7d411c)
const left = 0.42 * W;
const top = 0.16 * H;
const width = 0.17 * W;
const height = 0.14 * H;
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
