const api = require("../config/api");
const { request } = require("../utils/request");
module.exports = {
  register: (body, key) => request(api.register, { method: "POST", body, key }),
  login: (body) => request(api.login, { method: "POST", body }),
  me: () => request(api.me),
  logout: () => request(api.logout, { method: "POST" }),
};
