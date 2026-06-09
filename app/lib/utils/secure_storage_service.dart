import 'dart:convert';
import 'dart:io';
import 'package:path_provider/path_provider.dart';

class SecureStorageService {
  static const String _settingsFilename = '.moba_broker_settings.enc';
  static const String _xorKey = 'MOBA_TRADE_SECURE_VAULT_KEY_2026';

  /// Saves the broker login credentials to a secure obfuscated local file.
  static Future<void> saveCredentials({
    required String clientId,
    required String password,
    required String apiKey,
    required String totpSecret,
    required bool isLiveMode,
  }) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/$_settingsFilename');

      final Map<String, dynamic> credentialsMap = {
        'clientId': clientId,
        'password': password,
        'apiKey': apiKey,
        'totpSecret': totpSecret,
        'isLiveMode': isLiveMode,
      };

      final String rawJson = jsonEncode(credentialsMap);
      final String encryptedString = _encrypt(rawJson);

      await file.writeAsString(encryptedString);
    } catch (_) {
      // Quiet fail to prevent crashes on non-writable filesystems
    }
  }

  /// Retrieves the saved broker credentials, returning null if none exist or fail to parse.
  static Future<Map<String, dynamic>?> readCredentials() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/$_settingsFilename');

      if (!await file.exists()) return null;

      final String encryptedString = await file.readAsString();
      final String decryptedJson = _decrypt(encryptedString);

      return jsonDecode(decryptedJson) as Map<String, dynamic>;
    } catch (_) {
      return null;
    }
  }

  /// Deletes all saved credentials from the device storage.
  static Future<void> clearCredentials() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/$_settingsFilename');
      if (await file.exists()) {
        await file.delete();
      }
    } catch (_) {}
  }

  /// Saves the persistent state of the auto-trading bot.
  static Future<void> saveAutoBotState(bool isEnabled) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_autobot_state');
      await file.writeAsString(isEnabled ? 'true' : 'false');
    } catch (_) {}
  }

  /// Reads the persistent state of the auto-trading bot.
  static Future<bool> readAutoBotState() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_autobot_state');
      if (!await file.exists()) return false;
      final content = await file.readAsString();
      return content.trim() == 'true';
    } catch (_) {
      return false;
    }
  }

  /// Saves the persistent state of the auto-manage swing holdings feature.
  static Future<void> saveAutoManageSwingEnabled(bool isEnabled) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_automanage_swing_state');
      await file.writeAsString(isEnabled ? 'true' : 'false');
    } catch (_) {}
  }

  /// Reads the persistent state of the auto-manage swing holdings feature.
  static Future<bool> readAutoManageSwingEnabled() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_automanage_swing_state');
      if (!await file.exists()) return false;
      final content = await file.readAsString();
      return content.trim() == 'true';
    } catch (_) {
      return false;
    }
  }

  /// Saves the first-seen date (ISO 8601) for a holding symbol, used to calculate days held.
  static Future<void> saveHoldingBuyDate(String symbolToken, DateTime date) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_holding_dates.json');
      Map<String, dynamic> dates = {};
      if (await file.exists()) {
        try { dates = jsonDecode(await file.readAsString()); } catch (_) {}
      }
      // Only record the first time we see a holding — never overwrite earlier dates
      if (!dates.containsKey(symbolToken)) {
        dates[symbolToken] = date.toIso8601String();
        await file.writeAsString(jsonEncode(dates));
      }
    } catch (_) {}
  }

  /// Reads the first-seen date for a holding symbol. Returns null if not recorded.
  static Future<DateTime?> readHoldingBuyDate(String symbolToken) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_holding_dates.json');
      if (!await file.exists()) return null;
      final Map<String, dynamic> dates = jsonDecode(await file.readAsString());
      final String? iso = dates[symbolToken];
      if (iso == null) return null;
      return DateTime.tryParse(iso);
    } catch (_) {
      return null;
    }
  }

  /// Removes a holding buy date record (call when a holding is fully liquidated).
  static Future<void> clearHoldingBuyDate(String symbolToken) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_holding_dates.json');
      if (!await file.exists()) return;
      Map<String, dynamic> dates = {};
      try { dates = jsonDecode(await file.readAsString()); } catch (_) {}
      dates.remove(symbolToken);
      await file.writeAsString(jsonEncode(dates));
    } catch (_) {}
  }

  /// Saves the custom Quant Server IP address.
  static Future<void> saveServerIp(String ip) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_server_ip');
      await file.writeAsString(ip.trim());
    } catch (_) {}
  }

  /// Reads the custom Quant Server IP address (defaults to 'moba-trade.onrender.com').
  static Future<String> readServerIp() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_server_ip');
      if (!await file.exists()) return 'moba-trade.onrender.com';
      final content = await file.readAsString();
      return content.trim().isEmpty ? 'moba-trade.onrender.com' : content.trim();
    } catch (_) {
      return 'moba-trade.onrender.com';
    }
  }

  /// Saves the persistent state of the portfolio safeguard enabled status.
  static Future<void> saveSafeguardEnabled(bool isEnabled) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_safeguard_enabled');
      await file.writeAsString(isEnabled ? 'true' : 'false');
    } catch (_) {}
  }

  /// Reads the persistent state of the portfolio safeguard enabled status (defaults to true).
  static Future<bool> readSafeguardEnabled() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_safeguard_enabled');
      if (!await file.exists()) return true; // Enabled by default
      final content = await file.readAsString();
      return content.trim() == 'true';
    } catch (_) {
      return true;
    }
  }

  /// Saves the persistent state of the market hours guard enabled status.
  static Future<void> saveMarketHoursGuardEnabled(bool isEnabled) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_market_hours_guard_enabled');
      await file.writeAsString(isEnabled ? 'true' : 'false');
    } catch (_) {}
  }

  /// Reads the persistent state of the market hours guard enabled status (defaults to true).
  static Future<bool> readMarketHoursGuardEnabled() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_market_hours_guard_enabled');
      if (!await file.exists()) return true; // Enabled by default
      final content = await file.readAsString();
      return content.trim() == 'true';
    } catch (_) {
      return true;
    }
  }

  /// Encrypts input using XOR and Base64 encoding.
  static String _encrypt(String input) {
    final List<int> bytes = utf8.encode(input);
    final List<int> keyBytes = utf8.encode(_xorKey);
    final List<int> result = List<int>.filled(bytes.length, 0);
    for (int i = 0; i < bytes.length; i++) {
      result[i] = bytes[i] ^ keyBytes[i % keyBytes.length];
    }
    return base64Encode(result);
  }

  /// Decrypts input using Base64 decoding and XOR.
  static String _decrypt(String base64input) {
    final List<int> bytes = base64Decode(base64input);
    final List<int> keyBytes = utf8.encode(_xorKey);
    final List<int> result = List<int>.filled(bytes.length, 0);
    for (int i = 0; i < bytes.length; i++) {
      result[i] = bytes[i] ^ keyBytes[i % keyBytes.length];
    }
    return utf8.decode(result);
  }
}
