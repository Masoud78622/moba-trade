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

  /// Saves the custom Quant Server IP address.
  static Future<void> saveServerIp(String ip) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_server_ip');
      await file.writeAsString(ip.trim());
    } catch (_) {}
  }

  /// Reads the custom Quant Server IP address (defaults to 'localhost').
  static Future<String> readServerIp() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/.moba_server_ip');
      if (!await file.exists()) return 'localhost';
      final content = await file.readAsString();
      return content.trim().isEmpty ? 'localhost' : content.trim();
    } catch (_) {
      return 'localhost';
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
