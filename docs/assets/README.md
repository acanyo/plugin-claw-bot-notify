# 图标素材

图标以对话气泡和机器人组成主体，两侧括号表示 CLI 工具调用；微信绿色底（`#07C160`）、纯白色主体，配深灰色表情与天线、浅绿色括号，适用于插件列表和 Console 页头。

- `icon.svg`：512 × 512 画布的可编辑矢量源文件。
- `icon-1024.png`：1024 × 1024 高清版本，保留圆角外的透明区域。
- `../../src/main/resources/clawbot-logo.svg`：由 `plugin.yaml` 引用的插件图标，与矢量源文件保持一致。
- `../../src/main/resources/logo.png`：512 × 512 PNG 版本，用于 README 展示。
- `../../ui/src/assets/logo.svg`：同一矢量图标的前端副本，用于 Console 页头。
- `../../ui/src/components/ClawBotMenuIcon.vue`：Console 菜单使用的单色机器人，继承菜单文字颜色，在明暗模式中显示为黑白图标。

修改图标时同步三份彩色 SVG，并重新导出两个尺寸的 PNG。菜单图标保留相同机器人、对话气泡与命令括号造型，使用 `currentColor`，不带彩色背景。
