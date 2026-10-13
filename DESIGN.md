---
version: alpha
name: "LudoMada"
description: "A premium Madagascar Ludo arena: live 3D tabletop, midnight-blue glass surfaces, electric-blue actions, and restrained gold."
colors:
  primary: "#185CD2"
  secondary: "#081836"
  accent: "#22ABFF"
  gold: "#EDB83A"
  background: "#030C24"
  surface: "#0A1C3A"
  surface-elevated: "#112B50"
  success: "#13A670"
  danger: "#E13E47"
typography:
  sans:
    fontFamily: "sans"
  mono:
    fontFamily: "monospace"
rounded:
  sm: "9dp"
  md: "18dp"
  lg: "23dp"
  hero: "32dp"
spacing:
  screen-gutter: "20dp"
  section-gap: "14dp"
  control-gap: "8dp"
components:
  button: {}
  card: {}
  dialog: {}
  navigation: {}
---

# LudoMada Design System

## Overview

### Creative North Star

**Madagascar's premium night-time tabletop arena.** The interface should feel like a real Ludo table staged at blue hour: a live GLES3 environment and board carry the spectacle; restrained glass HUD panels carry the controls. The distinctive signature is the contrast between deep indigo glass, luminous blue edges, and one warm gold action color.

### Product context and register

- **Audience and primary job:** Malagasy Ludo players on Android; start a game, select a valid entry fee, access their real account/wallet, and find market, tournament, profile, and tutorial features.
- **Target market(s) and evidence:** Madagascar-first product, grounded in MGA amounts, Malagasy copy, and French-language UI in the Android app. Do not infer payment or eligibility capabilities from this visual brief.
- **Locale(s) and language policy:** French and Malagasy are first-class. Preserve the existing language-selection behavior and translated resources; English is only a fallback where the existing implementation defines one. New controls must use the localization mechanism rather than add isolated hard-coded text.
- **Usage scene:** Android phone, landscape orientation, variable display density and 720p-class viewport. The primary Home composition is authored on a normalized 1280×720 canvas and scaled to the available surface.
- **Register:** Product-first with a game-brand hero. The Home screen is expressive; Wallet, profile, settings, market, and other task screens prioritize clarity, status, and trustworthy actions.
- **Memorable signature:** The real live 3D board/environment fills the stage behind a compact glass HUD; it must not be replaced by a screenshot, flat mock board, or second painted board.
- **Restraint:** One main gold call-to-action, measured luminous edges, and high contrast. Keep navigation labels explicit; avoid adding decoration that competes with the board.
- **Anti-references:** Generic SaaS dashboards, pastel mobile-game templates, flat Canvas substitutes for the live 3D scene, fake wallet balances, decorative buttons without working actions, and duplicated boards.
- **Token ownership/runtime mapping:** Existing Android runtime code is canonical (Model B). These tokens mirror values in `MainActivity.kt` and `PremiumHeroViewV2.java`; the Kotlin helpers (`navy`, `royal`, `cyan`, `emerald`, `gold`) and the active Home canvas are the current implementation points. `DESIGN.md` documents these values; it is not a generated token source. If tokens are later centralized, update the runtime owner and this file in the same change.

## Colors

- **Primary / action blue — `primary` #185CD2:** primary controls and active navigation on task screens.
- **Midnight navy — `secondary` #081836:** branded dark surfaces and the deep game shell.
- **Electric blue — `accent` #22ABFF:** focused outlines, luminous edge accents, and secondary emphasis.
- **Gold — `gold` #EDB83A:** the principal play action, selected entry fee, and high-value game emphasis. Use sparingly.
- **Background — `background` #030C24:** the deepest Home-stage tone.
- **Glass surface — `surface` #0A1C3A:** translucent panel base; use an actual alpha when drawing overlays.
- **Raised surface — `surface-elevated` #112B50:** secondary panel and control layer.
- **Success — `success` #13A670:** successful/non-destructive progress.
- **Danger — `danger` #E13E47:** errors and destructive intent; do not signal errors with color alone.

Text on dark surfaces is white or a high-opacity white. Secondary copy may use reduced alpha but must remain readable on the actual rendered background. Blue-on-white task screens keep text dark; do not carry translucent white Home text into light cards. Selection always uses shape, label, or state as well as color. Runtime values currently include alpha-composited variants; keep their alpha when mirroring the glass treatment.

## Typography

Use Android's native `sans` / `Typeface.DEFAULT` family for broad system coverage. Bold is reserved for labels, money, selected values, section headings, and primary actions. Regular weight is used for descriptions and supporting instructions. Small uppercase eyebrow labels may be used in the 1280×720 Home composition; paragraph content on task screens should remain sentence case. Keep currency values readable and never truncate the only visible representation of an account value without an accessible full-value path.

## Layout

- **Home hero:** normalized 1280×720 layout; live 3D scene remains full-bleed. Glass header, left task rail, right play/action rail, and an entry-fee selector make the hierarchy predictable.
- **Viewport behavior:** retain landscape orientation for game/Home surfaces as implemented. Scale the composition as one unit; do not independently move visual CTA bounds unless the corresponding touch mapping and Ramus tests are updated together.
- **Task screens:** use the established scrollable vertical page shell and consistent screen gutters. Reserve natural document scrolling where content exceeds the available height.
- **Safe areas:** account for system insets on non-game screens; do not cover critical controls with status/navigation bars or the on-screen keyboard.
- **Stability:** loading or refreshing the real wallet value must not fabricate a balance or shift the primary action. Use the existing server-backed balance path.

## Elevation & Depth

The Home uses translucent indigo panels, fine blue-white outlines, modest soft shadows, and a continuous real 3D environment beneath the UI. Panel borders separate controls from the scene; avoid heavy shadows on every child. Task-screen cards use white-to-pale-blue surfaces with a thin cool border and restrained elevation. Do not fake depth with a second board illustration or static hero background.

## Shapes

The Home glass frame uses a large radius (32dp in its normalized drawing units), with panel corners around 18dp. Task-screen buttons and cards use generous rounded edges (18–23dp). Fee chips use a tighter 9dp radius. Keep border strokes fine; reserve full gold fills for a selected fee or the primary play CTA. Never make controls so pill-shaped that labels become cramped on the 720px-height reference viewport.

## Components

### Foundational visual states

Enabled actions must have a clear target and visible pressed/selected state. Preserve existing touch scale feedback on Android buttons. A selected entry fee is gold-filled and visibly distinct; non-selected fees remain navy with a restrained blue outline. Disabled/unavailable actions must not be disguised as active ones. Loading, empty, offline, and API-failure states must explain what the player can do next.

### Buttons and actions

Primary emphasis is gold on the Home hero and blue on secondary task-screen actions. Label actions with their actual outcomes (for example, “JOUER”, “WALLET”, or “Créer une room”), and keep the same verb through the interaction. Avoid multiple equally bright primary CTAs in one panel. Money-moving, account, and other sensitive actions must retain their existing server-side authorization and confirmation behavior.

### Navigation and data display

The Home rail groups create/join, wallet, and profile tasks; the right rail groups play, tournaments, and tutorials. Market access remains a distinct action. Keep active navigation states explicit, and avoid icon-only navigation unless an accessible name is supplied. Real account balances come from the backend; never display decorative sample amounts as if they were real.

### Forms and overlays

Reuse the current Android dialog and form paths instead of creating screen-local duplicates. Authentication, wallet, market purchases, referral, and game-entry behavior are domain-sensitive and out of scope for visual-only changes. Retain existing validation, confirmation, and recovery logic. Dialog copy must identify the action and consequence in the active locale.

### Iconography

Use concise, familiar symbols paired with text labels for high-value or unfamiliar actions. The existing game pieces, board markers, and market GLB objects are domain illustrations, not generic interface icons. Keep decorative glyphs from becoming the only accessible name.

### Motion

Motion should describe a game state or acknowledge input, not constantly distract. Existing button press feedback scales to approximately 0.985 and returns to 1.0; retain short timings and keep touch handling responsive. The 3D scene may provide ambient motion, but static task screens should stay calm. Respect Android animator/reduced-motion settings where supported before introducing additional motion.

### Content and data visualization

The brand slogan is **“Joue. Gagne. Grandis.”** Keep French copy direct, brief, and consistent. Malagasy translations should sound natural rather than literal; review translation changes in context. Format amounts as integer MGA/Ar values with readable grouping. Do not invent account balances, player levels, win rates, tournament counts, cosmetic ownership, or payment-provider status.

## Do's and Don'ts

- **Do:** Keep the live GLES3 board/environment as the Home screen's primary visual.
- **Do:** Use midnight blue, electric blue, and a restrained gold accent consistently across Home and task screens.
- **Don't:** Replace the live scene with an image, flat board, or non-interactive mockup.
- **Don't:** change gameplay state, entry-fee rules, wallet/ledger, auth, MLG, payments, anti-cheat, or server contracts as part of a design-only task.
- **Don't:** adjust Home canvas hitbox geometry without updating interaction tests and checking real-device/Ramus evidence.
