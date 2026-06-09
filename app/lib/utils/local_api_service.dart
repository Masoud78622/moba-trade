import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'secure_storage_service.dart';

class LocalApiService {
  final HttpClient _client = HttpClient();

  LocalApiService() {
    _client.connectionTimeout = const Duration(seconds: 5); // Accommodate cloud API latency and cold starts
  }

  Future<String> _getServerUrl() async {
    final ip = await SecureStorageService.readServerIp();
    
    // 1. If it's already a full URL, use it directly
    if (ip.startsWith('http://') || ip.startsWith('https://')) {
      return ip;
    }
    
    // 2. Android emulator local loopback check
    if (ip == 'localhost' && Platform.isAndroid) {
      return 'http://10.0.2.2:8080';
    }
    
    // 3. Render or any standard cloud domain check
    if (ip.contains('onrender.com') || ip.contains('.com') || ip.contains('.net') || ip.contains('.org')) {
      return 'https://$ip';
    }
    
    // 4. Fallback for raw local IPs or localhost
    return 'http://$ip:8080';
  }

  /// Checks if the local Kotlin HTTP engine is running
  Future<bool> isServerOnline() async {
    try {
      final url = await _getServerUrl();
      final request = await _client.getUrl(Uri.parse('$url/status'));
      final response = await request.close();
      if (response.statusCode == 200) {
        final jsonString = await response.transform(utf8.decoder).join();
        final data = jsonDecode(jsonString);
        return data['status'] == 'ONLINE';
      }
    } catch (e) {
      debugPrint('🤖 MOBA LocalApiService: isServerOnline failed: $e');
    }
    return false;
  }

  /// Fetches live signals computed by the Kotlin ConfluenceScorer engine
  Future<List<Map<String, dynamic>>?> fetchLiveSignals() async {
    try {
      final url = await _getServerUrl();
      final request = await _client.getUrl(Uri.parse('$url/signals'));
      final response = await request.close();
      if (response.statusCode == 200) {
        final jsonString = await response.transform(utf8.decoder).join();
        final List<dynamic> data = jsonDecode(jsonString);
        
        return data.map((sig) {
          final String symbol = sig['symbol'] ?? 'UNKNOWN';
          final String? token = sig['token']?.toString();
          final int score = sig['score'] ?? 0;
          final String direction = sig['direction'] ?? 'HOLD';
          final bool compliant = sig['compliant'] ?? false;
          final List<dynamic> triggersList = sig['triggers'] ?? [];
          final String priceStr = sig['price'] ?? '₹0.00';

          // Map triggers representation into active strategy display strings
          String primaryStrategy = 'Confluence Analyzer';
          if (triggersList.isNotEmpty) {
            primaryStrategy = triggersList.first.toString().split('(')[0].trim();
          }

          // Build dynamic status matching trade criteria
          String status = 'WAITING FOR CONFIRMATION';
          if (!compliant) {
            status = 'REJECTED (NON-SHARIAH)';
          } else if (direction == 'BUY' && score >= 4) {
            status = 'ORDER PLACED';
          } else if (direction == 'HOLD') {
            status = 'NO CONFLUENCE';
          }

          return {
            'symbol': symbol,
            'token': token,
            'strategy': primaryStrategy,
            'score': score,
            'price': priceStr,
            'status': status,
            'time': 'JUST NOW',
            'compliant': compliant,
            'triggers': triggersList.map((t) => t.toString()).toList(),
          };
        }).toList();
      } else {
        debugPrint('🤖 MOBA LocalApiService: fetchLiveSignals non-200 code: ${response.statusCode}');
      }
    } catch (e) {
      debugPrint('🤖 MOBA LocalApiService: fetchLiveSignals failed: $e');
    }
    return null;
  }

  /// Fetches compliant stock universe size dynamically
  Future<int?> fetchCompliantStockCount() async {
    try {
      final url = await _getServerUrl();
      final request = await _client.getUrl(Uri.parse('$url/status'));
      final response = await request.close();
      if (response.statusCode == 200) {
        final jsonString = await response.transform(utf8.decoder).join();
        final data = jsonDecode(jsonString);
        return data['complianceDatabaseSize'] as int?;
      }
    } catch (e) {
      debugPrint('🤖 MOBA LocalApiService: fetchCompliantStockCount failed: $e');
    }
    return null;
  }

  Future<Map<String, dynamic>?> getAutoBotStatus() async {
    try {
      final url = await _getServerUrl();
      final request = await _client.getUrl(Uri.parse('$url/autobot/status'));
      final response = await request.close();
      if (response.statusCode == 200) {
        final jsonString = await response.transform(utf8.decoder).join();
        return jsonDecode(jsonString);
      }
    } catch (e) {
      debugPrint('🤖 MOBA LocalApiService: getAutoBotStatus failed: $e');
    }
    return null;
  }

  Future<bool> toggleAutoBot({bool? isEnabled, bool? isSwingManageEnabled}) async {
    try {
      final url = await _getServerUrl();
      final request = await _client.postUrl(Uri.parse('$url/autobot/toggle'));
      request.headers.set('Content-Type', 'application/json');
      
      final body = <String, dynamic>{};
      if (isEnabled != null) body['isEnabled'] = isEnabled;
      if (isSwingManageEnabled != null) body['isSwingManageEnabled'] = isSwingManageEnabled;
      
      request.write(jsonEncode(body));
      final response = await request.close();
      return response.statusCode == 200;
    } catch (e) {
      debugPrint('🤖 MOBA LocalApiService: toggleAutoBot failed: $e');
    }
    return false;
  }

  Future<Map<String, dynamic>?> fetchLearningReport() async {
    try {
      final url = await _getServerUrl();
      final request = await _client.getUrl(Uri.parse('$url/learning/report'));
      final response = await request.close();
      if (response.statusCode == 200) {
        final jsonString = await response.transform(utf8.decoder).join();
        return jsonDecode(jsonString);
      }
    } catch (e) {
      debugPrint('🤖 MOBA LocalApiService: fetchLearningReport failed: $e');
    }
    return null;
  }
}
