Component({
  properties: { title: String, message: String, action: String, busy: Boolean },
  methods: { retry() { this.triggerEvent("action"); } },
});
