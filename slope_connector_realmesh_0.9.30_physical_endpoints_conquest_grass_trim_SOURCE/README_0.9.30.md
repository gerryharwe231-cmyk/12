# 0.9.30 Physical Endpoints / Conquest Grass Trim

本版只针对 0.9.29 的以下问题修复，不改 0.9.23 原弧方块连接杖面板与已有设置逻辑：

- 首尾模型方块与中间弧段视觉/光照路径不同；
- 首尾端点在加宽后，两侧只有虚拟渲染，没有真实方块/碰撞；
- 端点顶部/侧面偶发透明，底面或相邻面 Z-fighting；
- 自动弧边裁切与加宽端点占用同一空间时穿模；
- Conquest Reforged 草方块被 ArcTrim 后继续带无碰撞装饰草，且裁切材质与周围草不一致；
- 新增真实端点伴随方块后可能产生的性能开销。

## 1. 端点侧面宽度现在是真实方块

0.9.29 的多格端点只是 `ModelBlockRenderer` 在一个 BlockEntity 里虚拟复制多份模型。视觉上看起来宽了，但两侧没有 BlockState、BlockEntity、碰撞和选框，因此会与 ArcTrim / 草地等实际世界方块重叠。

0.9.30 新增 `EndpointCompanionManager`：

- 依据端点共享弧截面的 lateral 轴，真正放置一排 ModelBlock；
- 每个侧向 tile 都是独立真实方块，有 outline/collision；
- 只物理化“侧面宽度”这一排，不生成 `宽度 × 高度` 二维 BlockEntity 阵列；
- 最大物理侧向 tile 数 32；
- 缩窄/拆除根端点时，伴随方块自动清理并恢复被覆盖的原方块；
- 如果伴随位置原来是 ArcTrim，会先取出 ArcTrim 的原始 `sourceState`，避免恢复成没有 BlockEntity 数据的坏 ArcTrim。

纯白模板端点也走相同的端点 BakedQuad / 光照路径，因此首尾不再混用“原版方块渲染”和“中间弧 BER 渲染”。

## 2. 端点透明与闪烁

- 真实 companion 之间的内部面会剔除；
- skinned companion 的自定义 BakedModel 渲染同样识别同一 endpoint grid 并剔除内部面；
- 与完整实体方块相邻的边界面按 full-cube 关系剔除，避免端点底面和地面顶部共面 Z-fighting；
- 起点朝弧线的纵向端面、终点朝弧线的纵向端面属于内部接缝，不重复渲染；
- 端点仍保留实际 captured model 的碰撞，而不是恢复成虚拟宽度。

## 3. 自动裁切和端点加宽占位

ArcAutoTrim 在旧生成器内部先完成。0.9.30 在生成返回后同步真实 endpoint companions：如果侧向 companion 正好落在 ArcTrim 单元，会安全替换这个 ArcTrim，并记录它原来的 sourceState。

因此宽端点和切割方块不会再同时占据一个视觉空间。

## 4. Conquest Reforged 草方块

对 Conquest Reforged 1.5.2 的资源进行了专门检查。以下 6 个 blockstate 实际包含 `grass_block_ext / grass_block_ext2` 无碰撞装饰草 multipart：

- `clover_covered_grass`
- `clover_covered_grass_layer`
- `grass_block_layer`
- `grass_covered_limestone`
- `taiga_grass`
- `taiga_grass_layer`

这些方块被 ArcTrim 后改用 `ConquestGrassTrimRenderer`：

- 只读取 BakedModel 的 directional/cull-face body quad；
- 不读取 `face == null` 的 multipart 装饰 quad，因此上方无碰撞草模型消失；
- sprite 直接取原 source BlockState 实体面的 BakedQuad；
- tint index 继续交给 Minecraft BlockColors，因此草色仍按原方块/生物群系计算；
- down 面仍可保持原模型自己的底部材质；
- 其他普通 ArcTrim 完全不受这条特殊兼容逻辑影响。

## 5. 性能

增加真实端点碰撞后同时做了以下限制：

- companion 只生成侧向一排，不生成二维 BE 阵列；
- ModelBlockEntity 没有 ticker，不会每 tick 执行逻辑；
- companion 清理只在生成/重设端点时扫描 6 条短射线，不在帧循环中扫描世界；
- `ModelBlockRenderer` 按 BlockState + BakedModel identity 缓存已经解码的 BakedQuad；相同端点模型的多个 companion 不再每帧重复解码；
- Conquest ArcTrim 网格按 `renderRevision + sourceState + BakedModel identity` 缓存；
- companion 内部接触面剔除，减少无意义顶点提交；
- 继续保留 0.9.29 已有的 ArcRibbon frustum culling、revision-driven arc mesh cache、直线低采样 / 弧线高采样策略。

## 6. 保持不变

继续保留原面板及已有功能：两点/三点、正反弧向、当前面/内弧方向、自动裁切、上下厚度、侧面宽度、清空连接点、G 键、视角定向放置、模型渲染杖、楼梯/半砖状态优先级、Conquest 栏杆连接兼容、退化弧保护。

## 7. 编译

GitHub Actions 工作流名称：

```text
Build 0.9.30 Physical Endpoints Conquest Grass Trim
```

测试旧世界时建议重新生成正在考察的弧，因为 0.9.29 的端点没有 EndpointGrid NBT / 真实 companion 数据。
