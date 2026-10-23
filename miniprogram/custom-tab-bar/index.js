const { switchMain, tabs } = require("../utils/navigation");
Component({
  data: { selected: "", tabs },
  lifetimes: {
    attached() {
      const page = getCurrentPages().slice(-1)[0];
      if (page) this.setData({ selected: "/" + page.route });
    },
  },
  methods: {
    async select(event) {
      const route = event.currentTarget.dataset.route;
      if (route === this.data.selected || this._navigating) return;
      this._navigating = true;
      try { await switchMain(route); }
      catch (error) { wx.showToast({ title: error.message, icon: "none" }); }
      finally { this._navigating = false; }
    },
  },
});
