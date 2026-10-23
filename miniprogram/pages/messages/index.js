const {guard}=require("../../utils/account");const {syncTab}=require("../../utils/navigation");Page({onShow(){syncTab(this,"/pages/messages/index");guard();}});
