# LudoMada premium gameplay scene — asset handoff

The committed 3D source scene is revision 2 of the Higgsfield 3D Jutsu project.

## Geometry

Revision 2 contains:
- 52 path cells using the current Android renderer's exact 52-node visualPath ordering.
- 24 finish-lane cells.
- 4 physical home plinths.
- 4 colored center wedges plus a center cap.
- A square 5.05 m board inside a 5.60 m deep table.

The pawn and hydro-chrome die remain real meshes. They are not images projected onto the board.

## Material handoff

The runtime should map:
- colored pawns -> lacquer profile
- die -> hydro-plated chrome
- pips -> dark inset metal
- board/table -> separate roughness/material classes
- frame -> polished gold

Lane C owns material/lighting calibration. Lane A owns runtime mapping.

## Integration boundary

The scene is an asset source, not a second game-state implementation. The Android authoritative state and token coordinates remain in the existing gameplay code.

Do not use the reference image as the gameplay background. It is a visual target only.

## Source

Higgsfield 3D Jutsu:
project 083cab86-9817-4ddc-8d12-92d9212d008b
revision 2
