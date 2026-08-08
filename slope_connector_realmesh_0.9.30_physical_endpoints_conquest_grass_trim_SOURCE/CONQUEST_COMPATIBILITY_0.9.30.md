# Conquest Reforged 1.5.2 compatibility — 0.9.30

本版继续保留 0.9.29 的 Fence / Pane / Wall / Balustrade / Railings 原生邻居状态兼容，并新增 ArcTrim 草方块专用处理。

直接检查了 ConquestReforged Fabric 1.20.1-1.5.2 资源：`grass_block_layer.json` 的 solid body 与 `grass_block_ext` / `grass_block_ext2` decoration 位于不同 multipart apply 项；`grass_block_height16.json` 的实体面使用 `minecraft:block/grass_block_top`，非底面带 `tintindex: 0`。

0.9.30 不再让 ArcTrim 从 multipart null-face decorative quad 取材质，而是只取方向面 body quad 的 sprite/tint。因此装饰草被删除，但草块实体材质与 tint 保持来源方块本身。
