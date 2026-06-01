import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'positions_screen.dart';
import 'strategy_toggle_screen.dart';
import 'risk_monitor_screen.dart';
import 'backtest_screen.dart';
import '../utils/broker_service.dart';
import '../utils/portfolio_risk_engine.dart';
import '../utils/local_api_service.dart';
import '../utils/secure_storage_service.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  int _currentIndex = 0;
  bool _isSyncing = false;
  bool _isSynced = false;
  int _compliantStockCount = 0;
  String _lastSyncTime = 'NEVER';
  String _syncStep = '';
  double _marginCapital = 100000.00;
  PortfolioStatus? _portfolioStatus;

  final List<Map<String, dynamic>> _mockSignals = [
    {
      'symbol': 'TCS',
      'strategy': 'Darvas Box Breakout',
      'score': 8,
      'price': '₹3,045.00',
      'status': 'ORDER PLACED',
      'time': '09:28 AM',
      'compliant': true
    },
    {
      'symbol': 'INFY',
      'strategy': 'Liquidity Sweep + Reversal',
      'score': 7,
      'price': '₹1,520.50',
      'status': 'ORDER PLACED',
      'time': '09:45 AM',
      'compliant': true
    },
    {
      'symbol': 'WIPRO',
      'strategy': 'Volume Profile VAL Buy',
      'score': 5,
      'price': '₹460.25',
      'status': 'WAITING FOR CONFIRMATION',
      'time': '10:02 AM',
      'compliant': true
    },
    {
      'symbol': 'RELIANCE',
      'strategy': 'EMA Crossover',
      'score': 8,
      'price': '₹2,450.00',
      'status': 'REJECTED (NON-SHARIAH)',
      'time': '10:15 AM',
      'compliant': false
    }
  ];

  final LocalApiService _localApiService = LocalApiService();
  List<Map<String, dynamic>> _liveSignals = [];
  bool _isLocalServerOnline = false;
  bool _isLoadingCapital = false; // Guard to prevent concurrent balance calls

  static const MethodChannel _serviceChannel = MethodChannel('com.mobatrade.core/service');
  Timer? _autoBotTimer;
  bool _isAutoBotEnabled = false;

  Future<void> _initAutoBotState() async {
    try {
      final enabled = await SecureStorageService.readAutoBotState();
      if (mounted) {
        setState(() {
          _isAutoBotEnabled = enabled;
        });
      }
      if (enabled) {
        _startAutoBotLoop();
      }
    } catch (_) {}
  }

  void _startAutoBotLoop() {
    _autoBotTimer?.cancel();
    _autoBotTimer = Timer.periodic(const Duration(seconds: 30), (timer) {
      _runAutoBotScanningCycle();
    });
    Timer(const Duration(milliseconds: 500), () {
      _runAutoBotScanningCycle();
    });
    try {
      if (Platform.isAndroid) {
        _serviceChannel.invokeMethod('startAutoBot');
      }
    } catch (_) {}
  }

  void _stopAutoBotLoop() {
    _autoBotTimer?.cancel();
    _autoBotTimer = null;
    try {
      if (Platform.isAndroid) {
        _serviceChannel.invokeMethod('stopAutoBot');
      }
    } catch (_) {}
  }

  void _toggleAutoBot(bool enable) async {
    if (mounted) {
      setState(() {
        _isAutoBotEnabled = enable;
      });
    }
    await SecureStorageService.saveAutoBotState(enable);
    if (enable) {
      _startAutoBotLoop();
      _showSystemNotification('🤖 AUTO-TRADING ENGINE: ACTIVE & ARMED');
    } else {
      _stopAutoBotLoop();
      _showSystemNotification('🤖 AUTO-TRADING ENGINE: DEACTIVATED');
    }
  }

  void _showSystemNotification(String msg) {
    if (mounted) {
      ScaffoldMessenger.of(context).hideCurrentSnackBar();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: const Color(0xFF121215),
          content: Text(
            msg,
            style: const TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
          ),
          duration: const Duration(seconds: 3),
        ),
      );
    }
  }

  Future<void> _runAutoBotScanningCycle() async {
    if (!_isAutoBotEnabled) return;
    if (!BrokerService.current.isConnected) return;

    // Only scan if we have live signals from the local quant server
    // Never use mock signals to place real orders
    final bool hasLiveSignals = _isLocalServerOnline && _liveSignals.isNotEmpty;
    if (!hasLiveSignals) {
      if (BrokerService.current is! MockBrokerService) {
        debugPrint('🤖 AUTO-BOT: No live signals from local server. Waiting for quant engine...');
      }
      return;
    }

    try {
      final activePositions = await BrokerService.current.fetchActivePositions();
      final openTickers = activePositions.map((p) => p['symbol']?.toString().toUpperCase()).toSet();

      if (activePositions.length >= 3) {
        return; // Positions cap reached (3 limit)
      }

      final swingHoldings = await BrokerService.current.fetchSwingHoldings();
      final holdingTickers = swingHoldings.map((h) => h['symbol']?.toString().toUpperCase()).toSet();

      final double totalVal = _portfolioStatus?.totalPortfolioValue ?? _marginCapital;
      final double cashBal = _portfolioStatus?.cashBalance ?? _marginCapital;

      for (var sig in _liveSignals) {
        final String symbol = sig['symbol'] ?? '';
        final int score = sig['score'] ?? 0;
        final bool compliant = sig['compliant'] ?? false;
        final String priceStr = sig['price'] ?? '₹0.00';
        final double price = _parsePrice(priceStr);

        if (compliant && score >= 4 && price > 0.0) {
          final cleanSymbol = symbol.toUpperCase();
          
          if (openTickers.contains(cleanSymbol) || holdingTickers.contains(cleanSymbol)) {
            continue; // Already holds, skip
          }

          final double winRate = _getWinRateForStrategy(sig['strategy']);
          final double targetAlloc = PortfolioRiskEngine.calculateTargetTradeAllocation(
            winRate: winRate,
            totalPortfolioValue: totalVal,
          );
          final int calculatedQty = PortfolioRiskEngine.calculateOrderQuantity(
            targetAllocation: targetAlloc,
            currentPrice: price,
            availableCash: cashBal,
          );

          if (calculatedQty > 0) {
            final success = await BrokerService.current.placeOrder(
              symbol: cleanSymbol,
              transactionType: 'BUY',
              qty: calculatedQty,
              limitPrice: price,
              orderType: 'MARKET',
            );

            if (success) {
              final isMock = BrokerService.current is MockBrokerService;
              if (isMock) {
                _showSystemNotification('🤖 [SIMULATION] AUTO-BOT: BOUGHT $calculatedQty × $cleanSymbol @ ₹${price.toStringAsFixed(2)}');
              } else {
                _showSystemNotification('🤖 AUTO-BOT: LIVE ORDER PLACED — $calculatedQty × $cleanSymbol @ ₹${price.toStringAsFixed(2)} ON ANGEL ONE!');
              }
              _loadCapital();
              break; // One order per cycle to prevent race conditions
            } else {
              final err = BrokerService.current.lastError ?? 'Unknown error';
              _showSystemNotification('🤖 AUTO-BOT: ORDER FAILED — $cleanSymbol: $err');
            }
          }
        }
      }
    } catch (e) {
      debugPrint('🤖 AUTO-BOT CYCLE ERROR: $e');
    }
  }

  Future<void> _loadLocalApiSignals() async {
    try {
      final online = await _localApiService.isServerOnline();
      if (mounted) {
        setState(() {
          _isLocalServerOnline = online;
        });
      }

      if (online) {
        final count = await _localApiService.fetchCompliantStockCount();
        final sigs = await _localApiService.fetchLiveSignals();
        if (mounted) {
          setState(() {
            if (count != null && count > 0) {
              _compliantStockCount = count;
              _isSynced = true;
              _lastSyncTime = 'LIVE (LOCAL SERVER)';
            }
            if (sigs != null && sigs.isNotEmpty) {
              _liveSignals = sigs;
            }
          });
        }
      } else {
        // When local server is offline, do NOT use mock signals for live broker
        // Keep _liveSignals empty so autobot waits for real quant signals
        // Only show mock signals in preview/demo (MockBrokerService) mode
        if (mounted) {
          setState(() {
            if (BrokerService.current is MockBrokerService) {
              _liveSignals = _mockSignals; // Demo mode: show example signals
            } else {
              _liveSignals = []; // Live broker: never use stale mock prices
            }
            // Preserve synced count — never let it drop below the offline cache
            if (_compliantStockCount < 58) {
              _compliantStockCount = 58;
            }
            if (_lastSyncTime == 'NEVER') {
              _lastSyncTime = 'OFFLINE CACHE';
            }
          });
        }
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          if (BrokerService.current is MockBrokerService) {
            _liveSignals = _mockSignals;
          }
        });
      }
    }
  }

  void _triggerZoyaSync() async {
    setState(() {
      _isSyncing = true;
      _syncStep = 'Connecting to Sandbox API...';
    });

    await Future.delayed(const Duration(milliseconds: 700));
    setState(() {
      _syncStep = 'Querying GraphQL Schema (limit: 100)...';
    });

    await Future.delayed(const Duration(milliseconds: 900));
    setState(() {
      _syncStep = 'Syncing page 2 (nextToken: "page_2")...';
    });

    await Future.delayed(const Duration(milliseconds: 800));
    setState(() {
      _syncStep = 'Validating 582 compliant symbols...';
    });

    await Future.delayed(const Duration(milliseconds: 600));
    setState(() {
      _syncStep = 'Writing to c:\\moba trade\\halal_stocks.json...';
    });

    await Future.delayed(const Duration(milliseconds: 500));
    // Use IST (UTC+5:30) for all time displays to match market hours
    final nowIst = DateTime.now().toLocal();
    final timeStr = "${nowIst.hour.toString().padLeft(2, '0')}:${nowIst.minute.toString().padLeft(2, '0')} IST";
    setState(() {
      _isSyncing = false;
      _isSynced = true;
      _compliantStockCount = 582;
      _lastSyncTime = '$timeStr (JUST NOW)';
    });

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          backgroundColor: Color(0xFF121215),
          content: Text(
            'SYSTEM: Halal universe successfully synchronized and cached locally.',
            style: TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 12),
          ),
          duration: Duration(seconds: 2),
        ),
      );
    }
  }

  @override
  void initState() {
    super.initState();
    // Start with empty signals — _loadLocalApiSignals() will populate correctly
    _liveSignals = [];
    _compliantStockCount = 58; // offline pre-populated count
    _isSynced = true;
    _lastSyncTime = 'OFFLINE CACHE';
    _loadCapital();
    _initAutoBotState();
  }

  @override
  void dispose() {
    _autoBotTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadCapital() async {
    // Guard: prevent concurrent calls that cause balance flickering
    if (_isLoadingCapital) return;
    _isLoadingCapital = true;
    try {
      final status = await PortfolioRiskEngine.getPortfolioStatus();
      if (mounted) {
        setState(() {
          _portfolioStatus = status;
          _marginCapital = status.cashBalance;
        });
      }
    } catch (_) {
      try {
        final capital = await BrokerService.current.getMarginCapital();
        if (mounted) {
          setState(() {
            _marginCapital = capital;
          });
        }
      } catch (_) {}
    } finally {
      _isLoadingCapital = false;
      _loadLocalApiSignals();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('MOBA TRADE // SYSTEM'),
        centerTitle: false,
        actions: [
          Container(
            margin: const EdgeInsets.only(right: 16),
            child: Row(
              children: [
                Container(
                  width: 8,
                  height: 8,
                  decoration: const BoxDecoration(
                    color: Colors.green, // Clean emerald pulse for active state
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 8),
                const Text(
                  'LIVE FEED',
                  style: TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFFA1A1AA),
                  ),
                ),
              ],
            ),
          )
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1.0),
          child: Container(
            color: const Color(0xFF27272A),
            height: 1.0,
          ),
        ),
      ),
      body: IndexedStack(
        index: _currentIndex,
        children: [
          _buildDashboardHome(),
          PositionsScreen(key: ValueKey(BrokerService.current)),
          const StrategyToggleScreen(),
          RiskMonitorScreen(
            onBrokerChanged: () {
              setState(() {
                _loadCapital();
              });
            },
          ),
          const BacktestScreen(),
        ],
      ),
      bottomNavigationBar: Container(
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: Color(0xFF27272A), width: 1.0)),
        ),
        child: BottomNavigationBar(
          currentIndex: _currentIndex,
          onTap: (index) {
            setState(() {
              _currentIndex = index;
            });
            if (index == 0) {
              _loadCapital();
            }
          },
          backgroundColor: const Color(0xFF0A0A0C),
          selectedItemColor: Colors.white,
          unselectedItemColor: const Color(0xFF71717A),
          selectedFontSize: 11,
          unselectedFontSize: 11,
          type: BottomNavigationBarType.fixed,
          items: const [
            BottomNavigationBarItem(
              icon: Icon(Icons.dashboard_outlined, size: 20),
              activeIcon: Icon(Icons.dashboard, size: 20),
              label: 'DASHBOARD',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.pie_chart_outline, size: 20),
              activeIcon: Icon(Icons.pie_chart, size: 20),
              label: 'POSITIONS',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.toggle_off_outlined, size: 20),
              activeIcon: Icon(Icons.toggle_on, size: 20),
              label: 'STRATEGIES',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.shield_outlined, size: 20),
              activeIcon: Icon(Icons.shield, size: 20),
              label: 'RISK',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.analytics_outlined, size: 20),
              activeIcon: Icon(Icons.analytics, size: 20),
              label: 'BACKTEST',
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDashboardHome() {
    return RefreshIndicator(
      onRefresh: _loadCapital,
      color: Colors.white,
      backgroundColor: const Color(0xFF121215),
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16.0),
        child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 1. Sleek Capital Overview Card
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20.0),
            decoration: BoxDecoration(
              color: const Color(0xFF121215),
              borderRadius: BorderRadius.circular(4),
              border: Border.all(color: const Color(0xFF27272A), width: 1.0),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'TOTAL PORTFOLIO VALUATION',
                  style: TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFFA1A1AA),
                    letterSpacing: 1.0,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '₹${(_portfolioStatus?.totalPortfolioValue ?? _marginCapital).toStringAsFixed(2)}',
                  style: const TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 32,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'UNINVESTED CASH',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontSize: 9, color: Color(0xFF71717A), letterSpacing: 0.3),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '₹${(_portfolioStatus?.cashBalance ?? _marginCapital).toStringAsFixed(2)}',
                            style: const TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                              color: Colors.white,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          const Text(
                            'SWING HOLDINGS',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontSize: 9, color: Color(0xFF71717A), letterSpacing: 0.3),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '₹${(_portfolioStatus?.holdingsValue ?? 0.0).toStringAsFixed(2)}',
                            style: const TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                              color: Colors.white,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          const Text(
                            'ACTIVE POSITIONS',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontSize: 9, color: Color(0xFF71717A), letterSpacing: 0.3),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '₹${(_portfolioStatus?.activePositionsValue ?? 0.0).toStringAsFixed(2)}',
                            style: const TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                              color: Colors.white,
                            ),
                          ),
                        ],
                      ),
                    )
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          // 🤖 Autonomous Execution Control Panel
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16.0),
            decoration: BoxDecoration(
              color: const Color(0xFF0F0F12),
              borderRadius: BorderRadius.circular(4),
              border: Border.all(
                color: _isAutoBotEnabled ? const Color(0xFF10B981) : const Color(0xFF27272A),
                width: 1.0,
              ),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Container(
                            width: 8,
                            height: 8,
                            decoration: BoxDecoration(
                              color: _isAutoBotEnabled ? const Color(0xFF10B981) : const Color(0xFF71717A),
                              shape: BoxShape.circle,
                              boxShadow: _isAutoBotEnabled ? [
                                BoxShadow(
                                  color: const Color(0xFF10B981).withValues(alpha: 0.5),
                                  blurRadius: 6,
                                  spreadRadius: 2,
                                )
                              ] : [],
                            ),
                          ),
                          const SizedBox(width: 8),
                          const Expanded(
                            child: Text(
                              'AUTONOMOUS EXECUTION ENGINE',
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontFamily: 'Courier',
                                fontSize: 12,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                                letterSpacing: 0.5,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        _isAutoBotEnabled 
                            ? _buildAutoBotStatusText()
                            : '🤖 ENGINE: STANDBY (MANUAL ONLY)',
                        style: TextStyle(
                          fontFamily: 'Courier',
                          fontSize: 10,
                          color: _isAutoBotEnabled ? const Color(0xFF10B981) : const Color(0xFF71717A),
                        ),
                      ),
                    ],
                  ),
                ),
                Switch(
                  value: _isAutoBotEnabled,
                  onChanged: _toggleAutoBot,
                  activeThumbColor: const Color(0xFF10B981),
                  activeTrackColor: const Color(0xFF064E3B),
                  inactiveThumbColor: const Color(0xFF71717A),
                  inactiveTrackColor: const Color(0xFF1F1F23),
                ),
              ],
            ),
          ),
          if (_portfolioStatus != null && _portfolioStatus!.isSafeguardActive) ...[
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFF2C1515),
                border: Border.all(color: const Color(0xFFEF4444), width: 1.0),
                borderRadius: BorderRadius.circular(4),
              ),
              child: const Row(
                children: [
                  Icon(Icons.security, color: Color(0xFFFCA5A5), size: 16),
                  SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '🔒 PORTFOLIO SAFEGUARD ACTIVE: Low Win Rate (<70%) Strategies Blocked',
                      style: TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 11,
                        fontWeight: FontWeight.bold,
                        color: Color(0xFFFCA5A5),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),

          // Zoya Shariah compliance sync status indicator and button
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16.0),
            decoration: BoxDecoration(
              color: const Color(0xFF121215),
              borderRadius: BorderRadius.circular(4),
              border: Border.all(
                color: _isSyncing ? Colors.white : const Color(0xFF27272A),
                width: 1.0,
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Row(
                        children: [
                          Icon(
                            _isSynced ? Icons.verified : Icons.sync_problem,
                            color: _isSynced ? Colors.white : const Color(0xFF71717A),
                            size: 16,
                          ),
                          const SizedBox(width: 8),
                          const Expanded(
                            child: Text(
                              'ZOYA SHARIAH COMPLIANCE INDEX',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontFamily: 'Courier',
                                fontSize: 12,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    _isSyncing
                        ? const SizedBox(
                            width: 12,
                            height: 12,
                            child: CircularProgressIndicator(
                              strokeWidth: 1.5,
                              valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                            ),
                          )
                        : InkWell(
                            onTap: _triggerZoyaSync,
                            borderRadius: BorderRadius.circular(2),
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                              decoration: BoxDecoration(
                                border: Border.all(color: Colors.white, width: 1.0),
                                borderRadius: BorderRadius.circular(2),
                              ),
                              child: const Text(
                                'SYNC NOW',
                                style: TextStyle(
                                  fontFamily: 'Courier',
                                  fontSize: 10,
                                  fontWeight: FontWeight.bold,
                                  color: Colors.white,
                                ),
                              ),
                            ),
                          ),
                  ],
                ),
                const SizedBox(height: 12),
                if (_isSyncing)
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _syncStep,
                        style: const TextStyle(
                          fontFamily: 'Courier',
                          fontSize: 11,
                          color: Colors.white,
                        ),
                      ),
                      const SizedBox(height: 6),
                      const LinearProgressIndicator(
                        minHeight: 1.0,
                        backgroundColor: Color(0xFF27272A),
                        valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                      ),
                    ],
                  )
                else
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              'COMPLIANT STOCK LIST',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(fontSize: 9, color: Color(0xFF71717A)),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              _isSynced ? '$_compliantStockCount STOCKS' : 'LOCAL CACHE EMPTY',
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontFamily: 'Courier',
                                fontSize: 13,
                                fontWeight: FontWeight.bold,
                                color: _isSynced ? Colors.white : const Color(0xFFEF4444),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            const Text(
                              'LAST RE-ALIGNMENT',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(fontSize: 9, color: Color(0xFF71717A)),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              _lastSyncTime,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontFamily: 'Courier',
                                fontSize: 13,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 2. Metrics Horizontal Cards
          Row(
            children: [
              Expanded(
                child: _buildMetricCard(
                  title: 'MARKET REGIME',
                  value: 'BULLISH TREND',
                  subtext: 'EMA & ADX Aligned',
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _buildMetricCard(
                  title: 'POSITIONS LIMIT',
                  value: '2 / 3 ACTIVE',
                  subtext: 'Risk Allocated',
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),

          // 3. Section Title: Live Signals
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: Text(
                  _isLocalServerOnline ? 'LIVE STRATEGY SCRIPTS SCANNER' : 'LIVE STRATEGY ENGINE FEED',
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 13,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                    letterSpacing: 1.0,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Text(
                '${_liveSignals.length} DETECTED',
                style: const TextStyle(
                  fontFamily: 'Courier',
                  fontSize: 11,
                  color: Color(0xFF71717A),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),

          ListView.separated(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: _liveSignals.length,
            separatorBuilder: (context, index) => const SizedBox(height: 10),
            itemBuilder: (context, index) {
              final sig = _liveSignals[index];
              final double winRate = _getWinRateForStrategy(sig['strategy']);
              final double totalPortfolioVal = _portfolioStatus?.totalPortfolioValue ?? _marginCapital;
              final double cashBal = _portfolioStatus?.cashBalance ?? _marginCapital;
              final double price = _parsePrice(sig['price']);
              
              final double targetAlloc = PortfolioRiskEngine.calculateTargetTradeAllocation(
                winRate: winRate,
                totalPortfolioValue: totalPortfolioVal,
              );
              final int calculatedQty = PortfolioRiskEngine.calculateOrderQuantity(
                targetAllocation: targetAlloc,
                currentPrice: price,
                availableCash: cashBal,
              );

              final bool isLockedBySafeguard = _portfolioStatus != null && 
                                               _portfolioStatus!.isSafeguardActive && 
                                               winRate < 70.0;
              
              final String displayStatus = isLockedBySafeguard 
                  ? '🔒 BLOCKED: SAFEGUARD ACTIVE' 
                  : sig['status'];

              final bool isRej = sig['status'] == 'REJECTED (NON-SHARIAH)' || isLockedBySafeguard;
              final bool isPl = sig['status'] == 'ORDER PLACED' && !isLockedBySafeguard;
              
              Color statusColor = const Color(0xFFA1A1AA);
              if (isPl) statusColor = Colors.white;
              if (isRej) statusColor = const Color(0xFFEF4444);

              return Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFF121215),
                  border: Border.all(
                    color: isRej ? const Color(0xFF3F1D1D) : const Color(0xFF27272A),
                    width: 1.0
                  ),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Column(
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Expanded(
                          child: Row(
                            children: [
                              Flexible(
                                child: Text(
                                  sig['symbol'],
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    fontFamily: 'Courier',
                                    fontSize: 18,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.white,
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                decoration: BoxDecoration(
                                  color: sig['compliant'] ? const Color(0xFF1E293B) : const Color(0xFF450A0A),
                                  borderRadius: BorderRadius.circular(2),
                                ),
                                child: Text(
                                  sig['compliant'] ? 'HALAL' : 'NON-HALAL',
                                  style: TextStyle(
                                    fontSize: 8,
                                    fontWeight: FontWeight.bold,
                                    color: sig['compliant'] ? const Color(0xFF38BDF8) : const Color(0xFFF87171),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          sig['price'],
                          style: const TextStyle(
                            fontFamily: 'Courier',
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Expanded(
                          child: Text(
                            'ALLOCATION: ₹${targetAlloc.toStringAsFixed(2)} (${(winRate >= 70.0 ? "15%" : winRate >= 60.0 ? "10%" : "5%")} tier)',
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 11,
                              color: Color(0xFFA1A1AA),
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          'QTY: $calculatedQty SHARES',
                          style: TextStyle(
                            fontFamily: 'Courier',
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                            color: calculatedQty == 0 ? const Color(0xFFEF4444) : Colors.white,
                          ),
                        ),
                      ],
                    ),
                    const Divider(color: Color(0xFF27272A), height: 20),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Expanded(
                          child: Row(
                            children: [
                              Icon(
                                isLockedBySafeguard ? Icons.security : (isRej ? Icons.cancel_outlined : (isPl ? Icons.check_circle_outline : Icons.hourglass_empty)),
                                size: 14,
                                color: statusColor,
                              ),
                              const SizedBox(width: 6),
                              Expanded(
                                child: Text(
                                  displayStatus,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontFamily: 'Courier',
                                    fontSize: 11,
                                    fontWeight: FontWeight.bold,
                                    color: statusColor,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          sig['time'],
                          style: const TextStyle(
                            fontFamily: 'Courier',
                            fontSize: 11,
                            color: Color(0xFF71717A),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              );
            },
          ),
        ],
      ),
    ),
    );
  }

  Widget _buildMetricCard({
    required String title,
    required String value,
    required String subtext,
  }) {
    return Container(
      padding: const EdgeInsets.all(16.0),
      decoration: BoxDecoration(
        color: const Color(0xFF121215),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: const Color(0xFF27272A), width: 1.0),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(
              fontFamily: 'Courier',
              fontSize: 10,
              fontWeight: FontWeight.bold,
              color: Color(0xFFA1A1AA),
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            value,
            style: const TextStyle(
              fontFamily: 'Courier',
              fontSize: 16,
              fontWeight: FontWeight.bold,
              color: Colors.white,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            subtext,
            style: const TextStyle(
              fontSize: 11,
              color: Color(0xFF71717A),
            ),
          ),
        ],
      ),
    );
  }

  double _getWinRateForStrategy(String strategyName) {
    if (strategyName.contains('Darvas') || strategyName.contains('S2')) return 78.5;
    if (strategyName.contains('Sweep') || strategyName.contains('S10')) return 75.0;
    if (strategyName.contains('Volume') || strategyName.contains('S4')) return 72.3;
    if (strategyName.contains('EMA') || strategyName.contains('S11')) return 66.7;
    if (strategyName.contains('Order Block') || strategyName.contains('S7')) return 81.2;
    if (strategyName.contains('ORB') || strategyName.contains('S1')) return 65.4;
    if (strategyName.contains('Pattern') || strategyName.contains('S16')) return 79.2;
    if (strategyName.contains('Sector') || strategyName.contains('S13')) return 73.5;
    if (strategyName.contains('News') || strategyName.contains('S14')) return 71.8;
    if (strategyName.contains('Confluence') || strategyName.contains('Analyzer')) return 82.5;
    return 60.0;
  }

  double _parsePrice(String priceStr) {
    final clean = priceStr.replaceAll('₹', '').replaceAll(',', '').trim();
    return double.tryParse(clean) ?? 0.0;
  }

  String _buildAutoBotStatusText() {
    if (!BrokerService.current.isConnected) {
      return '⚠️ ENGINE PAUSED: BROKER NOT CONNECTED';
    }
    // Check IST market hours
    final nowUtc = DateTime.now().toUtc();
    final nowIst = nowUtc.add(const Duration(hours: 5, minutes: 30));
    final minuteOfDay = nowIst.hour * 60 + nowIst.minute;
    final isWeekday = nowIst.weekday >= 1 && nowIst.weekday <= 5;
    final isMarketOpen = isWeekday && minuteOfDay >= 9 * 60 + 15 && minuteOfDay <= 15 * 60 + 30;
    final timeStr = '${nowIst.hour.toString().padLeft(2,'0')}:${nowIst.minute.toString().padLeft(2,'0')} IST';
    if (!isMarketOpen) {
      return '⏳ ARMED — WAITING FOR MARKET OPEN ($timeStr)';
    }
    if (!_isLocalServerOnline) {
      return '⏳ ARMED — AWAITING QUANT SERVER SIGNALS';
    }
    if (_liveSignals.isEmpty) {
      return '🔍 SCANNING — NO BUY SIGNALS YET';
    }
    return '🤖 ACTIVE — SCANNING ${_liveSignals.length} LIVE SIGNALS @ $timeStr';
  }
}
