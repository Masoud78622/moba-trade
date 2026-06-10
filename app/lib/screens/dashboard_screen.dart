import 'dart:async';
import 'dart:io';
import 'dart:convert';
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
import '../utils/angel_one_service.dart';

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
  bool _isAutoManageSwingEnabled = false;

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

  Timer? _autoBotTimer;
  Timer? _signalsRefreshTimer;
  bool _isAutoBotEnabled = false;

  Future<void> _initAutoBotState() async {
    try {
      final status = await _localApiService.getAutoBotStatus();
      if (status != null) {
        if (mounted) {
          setState(() {
            _isAutoBotEnabled = status['isEnabled'] ?? false;
            _isAutoManageSwingEnabled = status['isSwingManageEnabled'] ?? false;
          });
        }
      }
    } catch (_) {}
  }

  void _toggleAutoManageSwing(bool enable) async {
    if (mounted) {
      setState(() {
        _isAutoManageSwingEnabled = enable;
      });
    }
    final success = await _localApiService.toggleAutoBot(isSwingManageEnabled: enable);
    if (success) {
      if (enable) {
        _showSystemNotification('🛡️ SWING AUTO-MANAGE: ENABLED ON BACKEND');
      } else {
        _showSystemNotification('🛡️ SWING AUTO-MANAGE: DEACTIVATED ON BACKEND');
      }
    } else {
      _showSystemNotification('⚠️ FAILED TO SYNC WITH BACKEND. IS SERVER RUNNING?');
    }
  }

  void _toggleAutoBot(bool enable) async {
    if (mounted) {
      setState(() {
        _isAutoBotEnabled = enable;
      });
    }
    final success = await _localApiService.toggleAutoBot(isEnabled: enable);
    if (success) {
      if (enable) {
        _showSystemNotification('🤖 AUTO-TRADING ENGINE: ACTIVE ON BACKEND');
      } else {
        _showSystemNotification('🤖 AUTO-TRADING ENGINE: DEACTIVATED ON BACKEND');
      }
    } else {
       _showSystemNotification('⚠️ FAILED TO START AUTO-TRADING. IS SERVER RUNNING?');
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

  void _showAiInsightsDialog() async {
    final report = await _localApiService.fetchLearningReport();
    if (!mounted) return;

    if (report == null || report.isEmpty) {
      _showSystemNotification('🧠 EOD Learning Engine has no data yet. Check back after 4:00 PM.');
      return;
    }

    showDialog(
      context: context,
      builder: (context) {
        return AlertDialog(
          backgroundColor: const Color(0xFF121215),
          title: const Text(
            '🧠 EOD AI LEARNING REPORT',
            style: TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
          ),
          content: SizedBox(
            width: double.maxFinite,
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: report.keys.length,
              itemBuilder: (context, index) {
                final strategy = report.keys.elementAt(index);
                final bonus = report[strategy];
                return Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4.0),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        strategy,
                        style: const TextStyle(fontFamily: 'Courier', color: Color(0xFFA1A1AA), fontSize: 12),
                      ),
                      Text(
                        '+$bonus',
                        style: const TextStyle(fontFamily: 'Courier', color: Color(0xFF10B981), fontSize: 12, fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
          actions: [
            TextButton(
              onPressed: () async {
                try {
                  final String downloadPath = '/storage/emulated/0/Download/MobaTrade_AI_Report_${DateTime.now().millisecondsSinceEpoch}.json';
                  final file = File(downloadPath);
                  await file.writeAsString(jsonEncode(report));
                  _showSystemNotification('✅ REPORT SAVED TO DOWNLOADS: $downloadPath');
                  if (context.mounted) Navigator.pop(context);
                } catch (e) {
                  _showSystemNotification('⚠️ FAILED TO SAVE REPORT: $e');
                }
              },
              child: const Text('DOWNLOAD TO PHONE', style: TextStyle(fontFamily: 'Courier', color: Color(0xFF10B981))),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('CLOSE', style: TextStyle(fontFamily: 'Courier', color: Colors.white)),
            ),
          ],
        );
      },
    );
  }

  // _runAutoBotScanningCycle removed (now handled by Kotlin backend)

  Future<void> _autoConnectBroker() async {
    try {
      final credentials = await SecureStorageService.readCredentials();
      final bool hasCreds = credentials != null;
      final bool isLiveMode = hasCreds ? (credentials['isLiveMode'] == true) : true;

      if (isLiveMode && !BrokerService.current.isConnected) {
        final String clientId = credentials?['clientId']?.toString() ?? 'AAAC764774';
        final String password = credentials?['password']?.toString() ?? '3112';
        final String apiKey = credentials?['apiKey']?.toString() ?? '8M5vqGDS';
        final String totpSecret = credentials?['totpSecret']?.toString() ?? 'K336YHYAV6NN5H2DYMPBBZ55NM';

        final service = AngelOneBrokerService();
        final success = await service.connect(
          clientId: clientId,
          password: password,
          apiKey: apiKey,
          totpSecret: totpSecret,
        );

        if (success) {
          BrokerService.current = service;
          if (mounted) {
            setState(() {});
          }
          _showSystemNotification('🛡️ AUTO-CONNECT: LIVE BROKER SESSION RE-ESTABLISHED');
          _loadCapital();
        } else {
          final err = service.lastError ?? 'Unknown error';
          _showSystemNotification('⚠️ AUTO-CONNECT FAILED: $err');
        }
      }
    } catch (e) {
      debugPrint('🤖 AUTO-CONNECT EXCEPTION: $e');
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
      _syncStep = 'Connecting to Quant Server...';
    });

    // Step 1: Check if the server is reachable
    final online = await _localApiService.isServerOnline();
    if (!online) {
      setState(() {
        _isSyncing = false;
        _syncStep = '';
      });
      _showSystemNotification('⚠️ SYNC FAILED: Quant server is offline. Check server URL.');
      return;
    }

    setState(() { _syncStep = 'Triggering server-side stock universe refresh...'; });

    // Step 2: Trigger the real backend scan (calls SelfLearningEngine + Zoya sync)
    final triggered = await _localApiService.fetchLearningReport();

    setState(() { _syncStep = 'Fetching updated stock count...'; });

    // Step 3: Fetch the real stock count from the live server
    final count = await _localApiService.fetchCompliantStockCount();
    final sigs = await _localApiService.fetchLiveSignals();

    final nowIst = DateTime.now().toLocal();
    final timeStr = '${nowIst.hour.toString().padLeft(2, '0')}:${nowIst.minute.toString().padLeft(2, '0')} IST';

    setState(() {
      _isSyncing = false;
      _isSynced = true;
      _compliantStockCount = count ?? _compliantStockCount;
      _lastSyncTime = '$timeStr (LIVE)';
      if (sigs != null && sigs.isNotEmpty) _liveSignals = sigs;
    });

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: const Color(0xFF121215),
          content: Text(
            'SYSTEM: Halal universe refreshed. ${count ?? 0} compliant stocks loaded from server.',
            style: const TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 12),
          ),
          duration: const Duration(seconds: 3),
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
    _autoConnectBroker();
    _loadCapital();
    _initAutoBotState();
    
    // Periodically refresh signals and server status every 15 seconds to keep dashboard up-to-date
    _signalsRefreshTimer = Timer.periodic(const Duration(seconds: 15), (timer) {
      _loadLocalApiSignals();
    });
  }

  @override
  void dispose() {
    _autoBotTimer?.cancel();
    _signalsRefreshTimer?.cancel();
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
                  decoration: BoxDecoration(
                    color: BrokerService.current.isConnected
                        ? const Color(0xFF10B981)  // Emerald green = live Angel One
                        : const Color(0xFFF59E0B), // Amber = simulation mode
                    shape: BoxShape.circle,
                    boxShadow: BrokerService.current.isConnected ? [
                      BoxShadow(
                        color: const Color(0xFF10B981).withValues(alpha: 0.5),
                        blurRadius: 5,
                        spreadRadius: 1,
                      )
                    ] : [],
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  BrokerService.current.isConnected ? 'LIVE FEED' : 'SIMULATION',
                  style: TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: BrokerService.current.isConnected
                        ? const Color(0xFF10B981)
                        : const Color(0xFFF59E0B),
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
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
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
                if (_isAutoBotEnabled) ...[
                  const Divider(color: Color(0xFF27272A), height: 24),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              'AUTO-MANAGE SWING HOLDINGS',
                              style: TextStyle(
                                fontFamily: 'Courier',
                                fontSize: 11,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                                letterSpacing: 0.5,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Builder(
                              builder: (context) {
                                final totalVal = _portfolioStatus?.totalPortfolioValue ?? _marginCapital;
                                final threshold = PortfolioRiskEngine.calculateSwingStopLossThreshold(totalVal);
                                String ruleReason = 'Account > ₹5L';
                                if (totalVal < 100000.0) {
                                  ruleReason = 'Account < ₹1L';
                                } else if (totalVal <= 500000.0) {
                                  ruleReason = 'Account ₹1L–₹5L';
                                }
                                return Text(
                                  '🛡️ Stop-Loss Cut: ${threshold.toStringAsFixed(1)}% ($ruleReason)',
                                  style: const TextStyle(
                                    fontFamily: 'Courier',
                                    fontSize: 9,
                                    color: Color(0xFFA1A1AA),
                                  ),
                                );
                              }
                            ),
                          ],
                        ),
                      ),
                      Switch(
                        value: _isAutoManageSwingEnabled,
                        onChanged: _toggleAutoManageSwing,
                        activeThumbColor: Colors.white,
                        activeTrackColor: const Color(0xFF27272A),
                        inactiveThumbColor: const Color(0xFF71717A),
                        inactiveTrackColor: const Color(0xFF1F1F23),
                      ),
                    ],
                  ),
                ],
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
                  value: _getMarketRegimeText(),
                  subtext: _getMarketRegimeSubtext(),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _buildMetricCard(
                  title: 'POSITIONS LIMIT',
                  value: _getPositionsLimitText(),
                  subtext: _getPositionsLimitSubtext(),
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
              if (_isLocalServerOnline)
                InkWell(
                  onTap: _showAiInsightsDialog,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: const Color(0xFF27272A),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: const Text(
                      '🧠 AI INSIGHTS',
                      style: TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
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
                        const Spacer(),
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

  String _getMarketRegimeText() {
    if (_liveSignals.isNotEmpty) {
      final sig = _liveSignals.first;
      final regime = sig['regime']?.toString();
      if (regime != null && regime.isNotEmpty) {
        if (regime == 'TRENDING_BULLISH') return 'BULLISH TREND';
        if (regime == 'TRENDING_BEARISH') return 'BEARISH TREND';
        if (regime == 'RANGING') return 'RANGING MARKET';
        if (regime == 'VOLATILE') return 'VOLATILE MARKET';
        return regime.replaceAll('_', ' ').toUpperCase();
      }
      return 'BULLISH TREND'; // default for mock signals
    }
    return 'DETERMINING...';
  }

  String _getMarketRegimeSubtext() {
    if (_liveSignals.isNotEmpty) {
      final sig = _liveSignals.first;
      final regime = sig['regime']?.toString();
      if (regime != null && regime.isNotEmpty) {
        if (regime == 'TRENDING_BULLISH') return 'Trend-Following Active';
        if (regime == 'TRENDING_BEARISH') return 'Warning: Buying Paused';
        if (regime == 'RANGING') return 'Mean-Reversion Active';
        if (regime == 'VOLATILE') return 'Warning: High Risk';
      }
      return 'EMA & ADX Aligned'; // default for mock signals
    }
    return 'Awaiting Quant Feed';
  }

  String _getPositionsLimitText() {
    final count = _portfolioStatus?.activePositionsCount ?? 0;
    return '$count / 3 ACTIVE';
  }

  String _getPositionsLimitSubtext() {
    final count = _portfolioStatus?.activePositionsCount ?? 0;
    if (count >= 3) {
      return 'Max Exposure Reached';
    }
    return 'Risk Capacity Open';
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
