Page({
  data: { module: "" },
  onLoad(options) {
    this.setData({
      module: /^[A-IS]$/.test(options.module || "") ? options.module : "D",
    });
  },
});
