var exec = require('cordova/exec');

exports.isAvailable = function (callback) {
  cordova.exec(callback, null, "GoogleSignInPlugin", "isAvailable", []);
};

exports.login = function (options, success, error) {
  exec(success, error, 'GoogleSignInPlugin', 'login', [options]);
};

exports.trySilentLogin = function (options, successCallback, errorCallback) {
  cordova.exec(successCallback, errorCallback, "GoogleSignInPlugin", "trySilentLogin", [options]);
};

exports.isSignedIn = function (success, error) {
  exec(success, error, 'GoogleSignInPlugin', 'isSignedIn');
};

exports.logout = function (success, error) {
  exec(success, error, 'GoogleSignInPlugin', 'signOut');
};

exports.disconnect = function (success, error) {
  exec(success, error, 'GoogleSignInPlugin', 'disconnect');
};
