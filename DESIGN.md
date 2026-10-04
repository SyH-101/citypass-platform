---
version: alpha
colors:
  ink: '#1f3445'
  canvas: '#eef4f8'
  surface: '#ffffff'
  primary: '#165f83'
  border: '#aabcc8'
  danger: '#a53142'
typography:
  display:
    fontFamily: 'Microsoft YaHei, PingFang SC, sans-serif'
  body:
    fontFamily: 'Segoe UI, Microsoft YaHei, sans-serif'
rounded:
  panel: '12px'
  control: '6px'
spacing:
  field: '12px'
  panel: '24px'
components:
  Button:
    minHeight: '44px'
  ImagePreview:
    aspectRatio: '4 / 3'
---
# CityPass 本地笔记工作台

## Overview
单页开发调试工具，面向中文 Java 学习者。用实际发布步骤和图片顺序表达结构；无管理员能力。没有现存前端，后端 API 是权限、状态与版本冲突的依据。

## Colors
蓝灰画布与深蓝文字对应城市活动手册。`src/main/resources/debug/story-files.css` 的 `:root` 变量是运行时 token owner；上面的数值记录该唯一实现。危险操作用文字和独立区域表达。

## Typography
中文无网络字体依赖。显示标题用微软雅黑，表单和状态用 Segoe UI 加中文后备，正文 16px、行高 1.6。

## Layout
上方登录，下方编辑和预览两列；760px 以下单列。图片用有序列表，上移/下移替代拖拽。页面自然滚动，反馈区保留高度。

## Elevation & Depth
面板靠边框区分，不采用大阴影、渐变和装饰数字。

## Shapes
面板 12px、控件 6px。预览固定 4:3，图片 contain。

## Components
字段、状态反馈、busy 包装由一个页面 JS 维护。Token 仅存在内存，输入默认隐藏，可显示。删除采用内联二次确认。失败保留编辑内容，409 提示刷新版本，不自动覆盖。

## Do's and Don'ts
按钮写实际动作；不用浏览器 alert/confirm。中文与键盘可达，focus 可见，遵从 reduced motion。公开部署默认关闭调试页。读取签名链接不写入日志或持久化存储。
