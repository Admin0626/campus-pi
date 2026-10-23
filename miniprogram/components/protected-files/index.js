const { request } = require("../../utils/request");
Component({
  properties: { files: { type: Array, value: [], observer: "load" } },
  data: { entries: [] },
  lifetimes: {
    attached() {
      this._active = true;
      this.load();
    },
    detached() {
      this._active = false;
      this._serial++;
    },
  },
  methods: {
    async load() {
      if (!this._active) return;
      const serial = (this._serial = (this._serial || 0) + 1);
      const token = getApp().globalData.token;
      const entries = await Promise.all(
        (this.properties.files || []).map(async (ref, index) => {
          try {
            let id;
            if (/^file:\/\/[0-9a-f-]{36}$/i.test(ref)) id = ref.slice(7);
            else {
              const match = String(ref).match(
                /\/file-content\/([0-9a-f-]{36})\//i,
              );
              if (!match) throw new Error("文件引用无效");
              id = match[1];
            }
            const value = await request("/files/" + id + "/access");
            const mime = await new Promise((resolve, reject) =>
              wx.request({
                url: value.url,
                method: "HEAD",
                timeout: 15000,
                success(r) {
                  if (r.statusCode !== 200)
                    return reject(new Error("文件访问失败，请重新获取"));
                  resolve(
                    r.header["Content-Type"] || r.header["content-type"] || "",
                  );
                },
                fail: () => reject(new Error("文件无法加载")),
              }),
            );
            return { index, url: value.url, mime, error: "" };
          } catch (e) {
            return { index, error: e.message };
          }
        }),
      );
      if (
        this._active &&
        this._serial === serial &&
        token === getApp().globalData.token
      )
        this.setData({ entries });
    },
    open(event) {
      const entry = this.data.entries[event.currentTarget.dataset.index];
      if (!entry?.url) return;
      wx.downloadFile({
        url: entry.url,
        timeout: 15000,
        success: (r) => {
          if (r.statusCode === 200)
            wx.openDocument({
              filePath: r.tempFilePath,
              fileType: "pdf",
              showMenu: false,
              fail: () => wx.showToast({ title: "文件无法预览", icon: "none" }),
            });
          else wx.showToast({ title: "文件已失效，请刷新", icon: "none" });
        },
        fail: () =>
          wx.showToast({ title: "下载失败，请刷新材料", icon: "none" }),
      });
    },
  },
});
