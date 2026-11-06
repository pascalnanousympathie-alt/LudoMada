# LudoMada Blender r7 asset source

The gameplay environment GLB is stored as ordered binary ZIP segments because the source binary is larger than the direct text transport limit.

- ZIP SHA-256: `de848f44936bf555c4210c8b9b7e7559eeeaa5a2fb14de795c9fa62cd58f4dcf`
- ZIP bytes: `462612`
- Segments: `35` files named `r7-000.bin` through `r7-034.bin`
- Segment lengths: 34 × 13,500 bytes, followed by 3,612 bytes
- Extracted GLB: `ludomada-gameplay-environment-r7.glb`
- GLB bytes: `2011296`
- GLB SHA-256: `1973b119d4ad02647c9cce9f193a37c25b0ae0ca778507f20b7078276f6d9cba`

`app/build.gradle.kts` reconstructs and verifies the archive, then exposes the extracted GLB as a generated Android asset at `assets/3d/ludomada-gameplay-environment-r7.glb`.
