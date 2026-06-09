import 'package:flutter/material.dart';
import 'dart:async';
import '../utils/broker_service.dart';

class PositionsScreen extends StatefulWidget {
  const PositionsScreen({super.key});

  @override
  State<PositionsScreen> createState() => _PositionsScreenState();
}

class _PositionsScreenState extends State<PositionsScreen> with SingleTickerProviderStateMixin {
  late TabController _tabController;
  Future<List<Map<String, dynamic>>>? _positionsFuture;
  Future<List<Map<String, dynamic>>>? _swingHoldingsFuture;
  Timer? _refreshTimer;

  // Manual SL/TP overrides keyed by symbol (survive tab switches)
  final Map<String, double> _slOverrides = {};
  final Map<String, double> _tpOverrides = {};

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _refreshPositions();
    _startAutoRefreshLoop();
  }

  void _startAutoRefreshLoop() {
    _refreshTimer?.cancel();
    _refreshTimer = Timer.periodic(const Duration(seconds: 30), (timer) {
      if (mounted) {
        _refreshPositions();
      }
    });
  }

  void _refreshPositions() {
    setState(() {
      _positionsFuture = BrokerService.current.fetchActivePositions();
      _swingHoldingsFuture = BrokerService.current.fetchSwingHoldings();
    });
  }

  @override
  void dispose() {
    _tabController.dispose();
    _refreshTimer?.cancel();
    super.dispose();
  }

  void _confirmLiquidate(String symbol, int qty, double currentPrice, {String? token}) {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        final isLive = BrokerService.current.isConnected;
        return AlertDialog(
          backgroundColor: const Color(0xFF121215),
          shape: RoundedRectangleBorder(
            side: const BorderSide(color: Color(0xFF27272A), width: 1.0),
            borderRadius: BorderRadius.circular(4),
          ),
          title: Text(
            isLive ? '✓ LIVE ORDER: LIQUIDATE $symbol' : 'SIMULATE: LIQUIDATE $symbol',
            style: const TextStyle(fontFamily: 'Courier', color: Colors.white, fontWeight: FontWeight.bold, fontSize: 14),
          ),
          content: Text(
            isLive 
              ? 'WARNING: This will submit a MARKET SELL order for $qty shares of $symbol to Angel One. The order will fill at the best available bid price (approx. ₹${currentPrice.toStringAsFixed(2)}).\n\nAre you sure you want to proceed?'
              : 'PREVIEW: This will simulate a paper-trade liquidation of $qty shares of $symbol at ₹${currentPrice.toStringAsFixed(2)}.\n\nProceed with simulation?',
            style: const TextStyle(fontFamily: 'Courier', color: Color(0xFFA1A1AA), fontSize: 12, height: 1.4),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('CANCEL', style: TextStyle(fontFamily: 'Courier', color: Color(0xFF71717A), fontWeight: FontWeight.bold)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: isLive ? const Color(0xFFEF4444) : Colors.white,
                foregroundColor: isLive ? Colors.white : Colors.black,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(2)),
              ),
              onPressed: () async {
                Navigator.pop(context);
                
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    backgroundColor: const Color(0xFF121215),
                    content: Row(
                      children: [
                        const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 1.5, valueColor: AlwaysStoppedAnimation<Color>(Colors.white)),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Text(
                            isLive ? 'SUBMITTING LIVE ORDER TO ANGEL ONE...' : 'SIMULATING ORDER EXECUTION...',
                            style: const TextStyle(fontFamily: 'Courier', color: Colors.white),
                          ),
                        ),
                      ],
                    ),
                  ),
                );

                final success = await BrokerService.current.placeOrder(
                  symbol: symbol,
                  transactionType: 'SELL',
                  qty: qty,
                  limitPrice: 0, // MARKET orders require price=0
                  orderType: 'MARKET', // Always use MARKET for liquidation — fills at best bid
                  token: token,
                );

                if (context.mounted) {
                  ScaffoldMessenger.of(context).hideCurrentSnackBar();
                  if (success) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(
                        backgroundColor: const Color(0xFF10B981),
                        content: Text(
                          isLive 
                            ? '✓ ORDER PLACED successfully on Angel One!'
                            : '✓ SIMULATION SUCCESS: Position closed paper-trading.',
                          style: const TextStyle(fontFamily: 'Courier', color: Colors.white, fontWeight: FontWeight.bold),
                        ),
                      ),
                    );
                    _refreshPositions();
                  } else {
                    final brokerErr = BrokerService.current.lastError ?? 'Unknown broker error';
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(
                        backgroundColor: const Color(0xFFEF4444),
                        duration: const Duration(seconds: 6),
                        content: Text(
                          '✗ SELL FAILED: $brokerErr',
                          style: const TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 11),
                        ),
                      ),
                    );
                  }
                }
              },
              child: Text(
                isLive ? 'SUBMIT SELL ORDER' : 'CONFIRM SIMULATION',
                style: const TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold),
              ),
            ),
          ],
        );
      },
    );
  }
  void _showAdjustDialog(String symbol, double currentSl, double currentTp) {
    final slController = TextEditingController(text: currentSl.toStringAsFixed(2));
    final tpController = TextEditingController(text: currentTp.toStringAsFixed(2));

    showDialog(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          backgroundColor: const Color(0xFF121215),
          shape: RoundedRectangleBorder(
            side: const BorderSide(color: Color(0xFF27272A), width: 1.0),
            borderRadius: BorderRadius.circular(4),
          ),
          title: Text(
            'ADJUST: $symbol',
            style: const TextStyle(
              fontFamily: 'Courier', color: Colors.white,
              fontWeight: FontWeight.bold, fontSize: 14,
            ),
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'Override the auto-calculated Stop-Loss and Target Price. Changes are saved locally and persist across refreshes.',
                style: TextStyle(fontFamily: 'Courier', color: Color(0xFFA1A1AA), fontSize: 11, height: 1.4),
              ),
              const SizedBox(height: 20),
              TextField(
                controller: slController,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                style: const TextStyle(fontFamily: 'Courier', color: Colors.white),
                cursorColor: Colors.white,
                decoration: const InputDecoration(
                  labelText: 'STOP LOSS (SL) ₹',
                  labelStyle: TextStyle(fontFamily: 'Courier', color: Color(0xFFA1A1AA), fontSize: 12),
                  enabledBorder: UnderlineInputBorder(borderSide: BorderSide(color: Color(0xFF27272A))),
                  focusedBorder: UnderlineInputBorder(borderSide: BorderSide(color: Colors.white)),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: tpController,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                style: const TextStyle(fontFamily: 'Courier', color: Colors.white),
                cursorColor: Colors.white,
                decoration: const InputDecoration(
                  labelText: 'TARGET PRICE (TP) ₹',
                  labelStyle: TextStyle(fontFamily: 'Courier', color: Color(0xFFA1A1AA), fontSize: 12),
                  enabledBorder: UnderlineInputBorder(borderSide: BorderSide(color: Color(0xFF27272A))),
                  focusedBorder: UnderlineInputBorder(borderSide: BorderSide(color: Colors.white)),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () {
                setState(() {
                  _slOverrides.remove(symbol);
                  _tpOverrides.remove(symbol);
                });
                Navigator.pop(ctx);
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    backgroundColor: Color(0xFF27272A),
                    content: Text('SL/TP reset to auto-calculated values.',
                      style: TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 11)),
                    duration: Duration(seconds: 2),
                  ),
                );
              },
              child: const Text('RESET', style: TextStyle(fontFamily: 'Courier', color: Color(0xFF71717A))),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('CANCEL', style: TextStyle(fontFamily: 'Courier', color: Color(0xFF71717A))),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.white,
                foregroundColor: Colors.black,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(2)),
              ),
              onPressed: () {
                final double? newSl = double.tryParse(slController.text.trim());
                final double? newTp = double.tryParse(tpController.text.trim());
                if (newSl == null || newTp == null) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      backgroundColor: Color(0xFFEF4444),
                      content: Text('Invalid values — please enter numeric prices.',
                        style: TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 11)),
                    ),
                  );
                  return;
                }
                setState(() {
                  _slOverrides[symbol] = newSl;
                  _tpOverrides[symbol] = newTp;
                });
                Navigator.pop(ctx);
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    backgroundColor: const Color(0xFF121215),
                    content: Text(
                      '✓ $symbol: SL → ₹${newSl.toStringAsFixed(2)}, TP → ₹${newTp.toStringAsFixed(2)}',
                      style: const TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
                    ),
                    duration: const Duration(seconds: 3),
                  ),
                );
              },
              child: const Text('SAVE', style: TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold)),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Tab bar header setup
        Container(
          decoration: const BoxDecoration(
            border: Border(bottom: BorderSide(color: Color(0xFF27272A), width: 1.0)),
          ),
          child: TabBar(
            controller: _tabController,
            indicatorColor: Colors.white,
            labelColor: Colors.white,
            unselectedLabelColor: const Color(0xFF71717A),
            labelStyle: const TextStyle(fontFamily: 'Courier', fontSize: 12, fontWeight: FontWeight.bold, letterSpacing: 1.0),
            tabs: const [
              Tab(text: 'INTRADAY'),
              Tab(text: 'SWING HOLDINGS'),
            ],
          ),
        ),
        Expanded(
          child: TabBarView(
            controller: _tabController,
            children: [
              _buildIntradayTab(),
              _buildSwingTab(),
            ],
          ),
        )
      ],
    );
  }

  Widget _buildIntradayTab() {
    return FutureBuilder<List<Map<String, dynamic>>>(
      future: _positionsFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(
            child: CircularProgressIndicator(
              valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
              strokeWidth: 2,
            ),
          );
        }
        if (snapshot.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24.0),
              child: Text(
                'ERROR LOADING POSITIONS: ${snapshot.error}',
                textAlign: TextAlign.center,
                style: const TextStyle(fontFamily: 'Courier', color: Color(0xFFEF4444), fontSize: 12),
              ),
            ),
          );
        }

        final positions = snapshot.data ?? [];

        return RefreshIndicator(
          onRefresh: () async {
            _refreshPositions();
            await _positionsFuture;
          },
          color: Colors.white,
          backgroundColor: const Color(0xFF121215),
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'ACTIVE DAY TRADES',
                      style: TextStyle(fontFamily: 'Courier', fontSize: 11, fontWeight: FontWeight.bold, color: Color(0xFFA1A1AA)),
                    ),
                    Row(
                      children: [
                        IconButton(
                          icon: const Icon(Icons.refresh, size: 14, color: Color(0xFFA1A1AA)),
                          onPressed: _refreshPositions,
                          padding: EdgeInsets.zero,
                          constraints: const BoxConstraints(),
                          tooltip: 'Sync Positions',
                        ),
                        const SizedBox(width: 8),
                        Text(
                          '${positions.length} TRADES',
                          style: const TextStyle(fontFamily: 'Courier', fontSize: 11, color: Color(0xFF71717A)),
                        ),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (positions.isEmpty)
                  const Center(
                    child: Padding(
                      padding: EdgeInsets.symmetric(vertical: 48.0),
                      child: Text(
                        'NO ACTIVE DAY POSITIONS',
                        style: TextStyle(fontFamily: 'Courier', fontSize: 12, color: Color(0xFF71717A)),
                      ),
                    ),
                  )
                else
                  ListView.separated(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    itemCount: positions.length,
                    separatorBuilder: (context, index) => const SizedBox(height: 12),
                    itemBuilder: (context, index) {
                      final pos = positions[index];
                      debugPrint('🤖 MOBA POSITIONS SCREEN INTRADAY: $pos');
                      final String sym = pos['symbol']?.toString() ?? 'UNKNOWN';
                      final double rawSl = (pos['sl'] as num?)?.toDouble() ?? 0.0;
                      final double rawTp = (pos['target'] as num?)?.toDouble() ?? 0.0;
                      final double sl = _slOverrides[sym] ?? rawSl;
                      final double target = _tpOverrides[sym] ?? rawTp;
                      final bool slOverridden = _slOverrides.containsKey(sym);
                      final bool tpOverridden = _tpOverrides.containsKey(sym);
                      final totalCost = pos['qty'] * pos['entry'];
                      final totalVal = pos['qty'] * pos['current'];
                      final pnl = totalVal - totalCost;
                      final pnlPercent = totalCost > 0 ? (pnl / totalCost) * 100 : 0.0;
                      final isProfit = pnl >= 0;

                      return _buildPositionCard(
                        symbol: sym,
                        qty: pos['qty'],
                        entry: pos['entry'],
                        current: pos['current'],
                        sl: sl,
                        target: target,
                        pnl: pnl,
                        pnlPercent: pnlPercent,
                        isProfit: isProfit,
                        badge: BrokerService.current.isConnected ? 'LIVE' : 'SIMULATION',
                        timeText: BrokerService.current.isConnected ? 'LIVE FEED (NSE)' : 'OPENED AT ${pos['time']}',
                        isCompliant: true,
                        daysHeld: null,
                        token: pos['token']?.toString(),
                        slOverridden: slOverridden,
                        tpOverridden: tpOverridden,
                      );
                    },
                  ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildSwingTab() {
    return FutureBuilder<List<Map<String, dynamic>>>(
      future: _swingHoldingsFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(
            child: CircularProgressIndicator(
              valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
              strokeWidth: 2,
            ),
          );
        }
        if (snapshot.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24.0),
              child: Text(
                'ERROR LOADING HOLDINGS: ${snapshot.error}',
                textAlign: TextAlign.center,
                style: const TextStyle(fontFamily: 'Courier', color: Color(0xFFEF4444), fontSize: 12),
              ),
            ),
          );
        }

        final holdings = snapshot.data ?? [];

        return RefreshIndicator(
          onRefresh: () async {
            _refreshPositions();
            await _swingHoldingsFuture;
          },
          color: Colors.white,
          backgroundColor: const Color(0xFF121215),
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'LONG TERM HOLDINGS',
                      style: TextStyle(fontFamily: 'Courier', fontSize: 11, fontWeight: FontWeight.bold, color: Color(0xFFA1A1AA)),
                    ),
                    Row(
                      children: [
                        IconButton(
                          icon: const Icon(Icons.refresh, size: 14, color: Color(0xFFA1A1AA)),
                          onPressed: _refreshPositions,
                          padding: EdgeInsets.zero,
                          constraints: const BoxConstraints(),
                          tooltip: 'Sync Holdings',
                        ),
                        const SizedBox(width: 8),
                        Text(
                          '${holdings.length} POSITIONS',
                          style: const TextStyle(fontFamily: 'Courier', fontSize: 11, color: Color(0xFF71717A)),
                        ),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (holdings.isEmpty)
                  const Center(
                    child: Padding(
                      padding: EdgeInsets.symmetric(vertical: 48.0),
                      child: Text(
                        'NO LONG TERM HOLDINGS',
                        style: TextStyle(fontFamily: 'Courier', fontSize: 12, color: Color(0xFF71717A)),
                      ),
                    ),
                  )
                else
                  ListView.separated(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    itemCount: holdings.length,
                    separatorBuilder: (context, index) => const SizedBox(height: 12),
                    itemBuilder: (context, index) {
                      final pos = holdings[index];
                      debugPrint('🤖 MOBA POSITIONS SCREEN HOLDING: $pos');
                      final String sym = pos['symbol']?.toString() ?? 'UNKNOWN';
                      final double entry = (pos['entry'] as num).toDouble();
                      final double current = (pos['current'] as num).toDouble();
                      final double rawSl = (pos['sl'] as num?)?.toDouble() ?? 0.0;
                      final double rawTp = (pos['target'] as num?)?.toDouble() ?? 0.0;
                      // Apply manual override if set
                      final double sl = _slOverrides[sym] ?? rawSl;
                      final double target = _tpOverrides[sym] ?? rawTp;
                      final bool slOverridden = _slOverrides.containsKey(sym);
                      final bool tpOverridden = _tpOverrides.containsKey(sym);
                      final int qty = (pos['qty'] as num).toInt();
                      final totalCost = qty * entry;
                      final totalVal = qty * current;
                      final pnl = totalVal - totalCost;
                      final pnlPercent = totalCost > 0 ? (pnl / totalCost) * 100 : 0.0;
                      final isProfit = pnl >= 0;

                      return _buildPositionCard(
                        symbol: sym,
                        qty: qty,
                        entry: entry,
                        current: current,
                        sl: sl,
                        target: target,
                        pnl: pnl,
                        pnlPercent: pnlPercent,
                        isProfit: isProfit,
                        badge: 'SWING HOLDING',
                        timeText: 'HELD FOR ${pos['daysHeld'] ?? 0} DAYS',
                        isCompliant: pos['compliant'] ?? false,
                        daysHeld: pos['daysHeld'] as int?,
                        token: pos['token']?.toString(),
                        slOverridden: slOverridden,
                        tpOverridden: tpOverridden,
                      );
                    },
                  ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildPositionCard({
    required String symbol,
    required int qty,
    required double entry,
    required double current,
    required double sl,
    required double target,
    required double pnl,
    required double pnlPercent,
    required bool isProfit,
    required String badge,
    required String timeText,
    required bool isCompliant,
    required int? daysHeld,
    String? token,
    bool slOverridden = false,
    bool tpOverridden = false,
  }) {
    final totalVal = qty * current;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF121215),
        border: Border.all(color: const Color(0xFF27272A), width: 1.0),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Text(
                      symbol,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 20,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Flexible(
                    child: Text(
                      '${isProfit ? "+" : ""}₹${pnl.toStringAsFixed(2)} (${isProfit ? "+" : ""}${pnlPercent.toStringAsFixed(2)}%)',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: isProfit ? const Color(0xFF10B981) : const Color(0xFFEF4444),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Wrap(
                spacing: 8,
                runSpacing: 4,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      border: Border.all(color: const Color(0xFF71717A), width: 0.5),
                      borderRadius: BorderRadius.circular(2),
                    ),
                    child: Text(
                      badge,
                      style: const TextStyle(fontSize: 8, fontWeight: FontWeight.bold, color: Color(0xFFA1A1AA)),
                    ),
                  ),
                  if (isCompliant)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(2),
                        border: Border.all(color: Colors.white, width: 0.5),
                      ),
                      child: const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.verified, size: 8, color: Colors.black),
                          SizedBox(width: 3),
                          Text(
                            'HALAL',
                            style: TextStyle(
                              fontSize: 8,
                              fontWeight: FontWeight.bold,
                              color: Colors.black,
                              letterSpacing: 0.5,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 12),
          _buildRow('QUANTITY', '$qty SHARES'),
          _buildRow('AVG ENTRY PRICE', '₹${entry.toStringAsFixed(2)}'),
          _buildRow('CURRENT MARKET PRICE', '₹${current.toStringAsFixed(2)}'),
          _buildRow('TOTAL POSITION VALUE', '₹${totalVal.toStringAsFixed(2)}'),
          
          const Divider(color: Color(0xFF27272A), height: 20),
          
          // SL / Target parameters
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        const Text(
                          'STOP LOSS (SL)',
                          style: TextStyle(fontFamily: 'Courier', fontSize: 10, color: Color(0xFF71717A)),
                        ),
                        if (slOverridden) ...[
                          const SizedBox(width: 4),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                            decoration: BoxDecoration(
                              color: const Color(0xFF1E293B),
                              borderRadius: BorderRadius.circular(2),
                            ),
                            child: const Text('MANUAL', style: TextStyle(fontFamily: 'Courier', fontSize: 7, color: Color(0xFF38BDF8), fontWeight: FontWeight.bold)),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '₹${sl.toStringAsFixed(2)}',
                      style: TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: slOverridden ? const Color(0xFF38BDF8) : Colors.white,
                      ),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      alignment: WrapAlignment.end,
                      children: [
                        if (tpOverridden) ...[
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                            decoration: BoxDecoration(
                              color: const Color(0xFF1E293B),
                              borderRadius: BorderRadius.circular(2),
                            ),
                            child: const Text('MANUAL', style: TextStyle(fontFamily: 'Courier', fontSize: 7, color: Color(0xFF38BDF8), fontWeight: FontWeight.bold)),
                          ),
                          const SizedBox(width: 4),
                        ],
                        const Text(
                          'TARGET (TP)',
                          style: TextStyle(fontFamily: 'Courier', fontSize: 10, color: Color(0xFF71717A)),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '₹${target.toStringAsFixed(2)}',
                      style: TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: tpOverridden ? const Color(0xFF38BDF8) : Colors.white,
                      ),
                    ),
                  ],
                ),
              )
            ],
          ),
          const Divider(color: Color(0xFF27272A), height: 20),
          Row(
            children: [
              if (daysHeld != null) ...[
                const Icon(Icons.access_time_filled, size: 12, color: Color(0xFFA1A1AA)),
                const SizedBox(width: 4),
              ] else ...[
                const Icon(Icons.bolt, size: 12, color: Color(0xFFA1A1AA)),
                const SizedBox(width: 4),
              ],
              Expanded(
                child: Text(
                  timeText,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFFA1A1AA),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              GestureButton(
                text: 'ADJUST',
                onTap: () => _showAdjustDialog(symbol, sl, target),
              ),
              const SizedBox(width: 8),
              GestureButton(
                text: 'LIQUIDATE',
                onTap: () {
                  _confirmLiquidate(symbol, qty, current, token: token);
                },
                isHighlight: true,
              ),
            ],
          )
        ],
      ),
    );
  }

  Widget _buildRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Expanded(
            child: Text(
              label,
              maxLines: 1,
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
            value,
            style: const TextStyle(
              fontFamily: 'Courier',
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: Colors.white,
            ),
          ),
        ],
      ),
    );
  }
}

class GestureButton extends StatelessWidget {
  final String text;
  final VoidCallback onTap;
  final bool isHighlight;

  const GestureButton({
    super.key,
    required this.text,
    required this.onTap,
    this.isHighlight = false,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: isHighlight ? Colors.white : Colors.transparent,
          border: Border.all(color: Colors.white, width: 1.0),
          borderRadius: BorderRadius.circular(2),
        ),
        child: Text(
          text,
          style: TextStyle(
            fontFamily: 'Courier',
            fontSize: 9,
            fontWeight: FontWeight.bold,
            color: isHighlight ? Colors.black : Colors.white,
          ),
        ),
      ),
    );
  }
}
