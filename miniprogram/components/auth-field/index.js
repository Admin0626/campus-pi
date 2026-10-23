Component({
  properties: {
    name: String, label: String, value: String, placeholder: String,
    glyph: { type: String, value: "user" }, disabled: Boolean,
    password: Boolean, revealed: Boolean, readonly: Boolean,
    unavailableName: String,
  },
  methods: {
    input(event) { this.triggerEvent("change", { field: this.properties.name, value: event.detail.value }); },
    reveal() { this.triggerEvent("reveal"); },
    unavailable() {
      if (this.properties.readonly) this.triggerEvent("unavailable", { name: this.properties.unavailableName });
    },
  },
});
