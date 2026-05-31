import 'dart:async';

abstract class BrokerService {
  /// Global accessor to the active broker service. Swappable at runtime.
  static BrokerService current = MockBrokerService();

  /// Holds the error message if the last login attempt failed.
  String? get lastError;

  /// Attempts to authenticate with the broker APIs using developer keys.
  Future<bool> connect({
    required String clientId,
    required String password,
    required String apiKey,
    required String totpSecret,
  });

  /// Safely terminates the broker session and returns to mock/preview mode.
  void disconnect();

  /// State query to determine if terminal is currently connected to live broker API.
  bool get isConnected;

  /// Retrieves the active available margin/capital from the profile.
  Future<double> getMarginCapital();

  /// Fetches standard active intraday/swing positions.
  Future<List<Map<String, dynamic>>> fetchActivePositions();

  /// Fetches the completed trades log for the trading day.
  Future<List<Map<String, dynamic>>> fetchCompletedTrades();

  /// Fetches the long-term/swing holdings in the DEMAT account.
  Future<List<Map<String, dynamic>>> fetchSwingHoldings();

  /// Submits an order request to the broker.
  /// [orderType] is 'LIMIT' (default) or 'MARKET'. Use MARKET for liquidations.
  /// For MARKET orders, set limitPrice to 0.
  Future<bool> placeOrder({
    required String symbol,
    required String transactionType, // BUY / SELL
    required int qty,
    required double limitPrice,
    String orderType = 'LIMIT',
  });

  /// Runs a comprehensive API diagnostics check and returns the results.
  Future<List<Map<String, String>>> runDiagnostics();
}

class MockBrokerService implements BrokerService {
  bool _connected = false;

  @override
  Future<List<Map<String, String>>> runDiagnostics() async {
    return [
      {'test': 'IP RESOLUTION', 'status': 'PASS', 'detail': 'SIMULATED: 192.168.1.100 (Local) | 203.0.113.50 (Public)'},
      {'test': 'AUTH TOKEN', 'status': 'PASS', 'detail': 'SIMULATED: Valid JWT session'},
      {'test': 'GET FUNDS (getRMS)', 'status': 'PASS', 'detail': 'SIMULATED: Available Balance: ₹100,000.00'},
      {'test': 'GET POSITIONS', 'status': 'PASS', 'detail': 'SIMULATED: 1 position active'},
      {'test': 'GET HOLDINGS', 'status': 'PASS', 'detail': 'SIMULATED: 2 demat holdings'},
      {'test': 'GET ORDER BOOK', 'status': 'PASS', 'detail': 'SIMULATED: Fetching success'},
    ];
  }

  final List<Map<String, dynamic>> _simulatedPositions = [
    {
      'symbol': 'TCS',
      'qty': 1,
      'entry': 3000.00,
      'current': 3045.00,
      'sl': 2900.00,
      'target': 3200.00,
      'time': '10:14 AM',
    }
  ];

  final List<Map<String, dynamic>> _simulatedCompletedTrades = [
    {'symbol': 'TCS', 'strategy': 'S7: Order Block', 'qty': 1, 'entry': 4100.0, 'exit': 4145.0, 'pnl': 45.0, 'win': true},
    {'symbol': 'INFY', 'strategy': 'S2: Darvas Box', 'qty': 2, 'entry': 1560.0, 'exit': 1572.5, 'pnl': 25.0, 'win': true},
    {'symbol': 'WIPRO', 'strategy': 'S10: Liquidity Sweep', 'qty': 5, 'entry': 480.0, 'exit': 487.25, 'pnl': 36.25, 'win': true},
  ];

  @override
  String? get lastError => null;

  @override
  Future<bool> connect({
    required String clientId,
    required String password,
    required String apiKey,
    required String totpSecret,
  }) async {
    // Simulate API authorization latency
    await Future.delayed(const Duration(milliseconds: 600));
    _connected = true;
    return true;
  }

  @override
  void disconnect() {
    _connected = false;
  }

  @override
  bool get isConnected => _connected;

  @override
  Future<double> getMarginCapital() async {
    double initialCap = 100000.00; // Match standard portfolio valuation
    for (var pos in _simulatedPositions) {
      if (pos['symbol'] != 'TCS') { // Skip default mock TCS position from capital deduction
        initialCap -= (pos['qty'] as int) * (pos['entry'] as double);
      }
    }
    return initialCap >= 0.0 ? initialCap : 0.0;
  }

  @override
  Future<List<Map<String, dynamic>>> fetchActivePositions() async {
    return List.from(_simulatedPositions);
  }

  @override
  Future<List<Map<String, dynamic>>> fetchCompletedTrades() async {
    return List.from(_simulatedCompletedTrades);
  }

  @override
  Future<List<Map<String, dynamic>>> fetchSwingHoldings() async {
    return [
      {
        'symbol': 'WIPRO',
        'qty': 45,
        'entry': 430.00,
        'current': 458.75,
        'sl': 415.00,
        'target': 480.00,
        'daysHeld': 5,
        'compliant': true
      },
      {
        'symbol': 'TCS',
        'qty': 2,
        'entry': 2950.00,
        'current': 3045.00,
        'sl': 2880.00,
        'target': 3250.00,
        'daysHeld': 12,
        'compliant': true
      }
    ];
  }

  @override
  Future<bool> placeOrder({
    required String symbol,
    required String transactionType,
    required int qty,
    required double limitPrice,
    String orderType = 'LIMIT',
  }) async {
    final cleanSymbol = symbol.toUpperCase();
    if (transactionType.toUpperCase() == 'BUY') {
      final exists = _simulatedPositions.any((p) => p['symbol'] == cleanSymbol);
      if (!exists) {
        _simulatedPositions.add({
          'symbol': cleanSymbol,
          'qty': qty,
          'entry': limitPrice,
          'current': limitPrice,
          'sl': limitPrice * 0.98,
          'target': limitPrice * 1.05,
          'time': 'JUST NOW (SIM)',
        });
        _simulatedCompletedTrades.add({
          'symbol': cleanSymbol,
          'strategy': 'SIMULATED AUTO-BUY',
          'qty': qty,
          'entry': limitPrice,
          'exit': limitPrice * 1.02,
          'pnl': (limitPrice * 0.02) * qty,
          'win': true,
        });
      }
    } else if (transactionType.toUpperCase() == 'SELL') {
      _simulatedPositions.removeWhere((p) => p['symbol'] == cleanSymbol);
    }
    return true;
  }
}
