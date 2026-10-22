# 插件一次选择多张图片

支持两种入口：

## 网页文件输入框

```html
<input id="images" type="file" accept="image/*" multiple>
<script>
document.getElementById('images').addEventListener('change', event => {
  const files = Array.from(event.target.files);
  // 按顺序处理全部 files；不要只取 files[0]。
});
</script>
```

`accept="image/*"` 直接打开系统相册选择器，按 `multiple` 支持多选并回传全部选中地址。无需全相册读取权限，也不额外设置数量上限；旧系统由 AndroidX 自动回退到可用的系统选择器。取消会结束本次选择；没有 `multiple` 的输入框继续单选。其他文件类型仍使用系统文件选择器。

## Bridge 图片选择 API

```javascript
const images = await Bridge.pickImages();
if (images === null) {
  // 无法打开选择器或读取图片失败。
} else {
  for (const base64 of images) {
    // 每一项都是原始图片的 Base64 字符串（不含 data: 前缀）。
  }
}
```

取消返回空数组 `[]`。原有 `Bridge.pickImage()` 继续返回单张图片字符串或 `null`；要多选的插件改用 `pickImages()` 并处理数组。使用新 API 前可以用 `typeof Bridge.pickImages === 'function'` 检测宿主支持情况。

实际可选数量及选择手势取决于设备提供的系统图片选择器。
