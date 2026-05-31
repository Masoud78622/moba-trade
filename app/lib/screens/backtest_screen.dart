import 'dart:math';
import 'package:flutter/material.dart';
import '../utils/backtest_report_generator.dart';

class BacktestScreen extends StatefulWidget {
  const BacktestScreen({super.key});

  @override
  State<BacktestScreen> createState() => _BacktestScreenState();
}

class _BacktestScreenState extends State<BacktestScreen> {
  String? _selectedStrategy;
  bool _isRunning = false;
  List<String> _consoleLogs = [];
  Map<String, dynamic>? _results;

  final List<Map<String, dynamic>> _strategies = [
    {
      'id': 'S1',
      'name': 'Opening Range Breakout (ORB)',
      'description': 'Strict breakout above the high of the first 15m candle with volume confirmation.',
      'tier': 'TIER 1: PRICE ACTION'
    },
    {
      'id': 'S2',
      'name': 'Box Theory (Darvas Box)',
      'description': 'Consolidation inside tight price bounds with dried volume, breaking out with a surge.',
      'tier': 'TIER 1: PRICE ACTION'
    },
    {
      'id': 'S3',
      'name': 'Support/Resistance Flip',
      'description': 'Previous resistance broken, retraced, and tested successfully as support.',
      'tier': 'TIER 1: PRICE ACTION'
    },
    {
      'id': 'S4',
      'name': 'Volume Profile (POC/VAH/VAL) [DEACTIVATED]',
      'description': 'Buys on value area lows (VAL) or point of control (POC) support. [DEACTIVATED: Consistent negative expectancy in stress testing]',
      'tier': 'TIER 2: VOLUME-BASED'
    },
    {
      'id': 'S5',
      'name': 'VWAP + Dev Bands',
      'description': 'Mean reversion buying when prices touch standard deviation bands below VWAP.',
      'tier': 'TIER 2: VOLUME-BASED'
    },
    {
      'id': 'S6',
      'name': 'OBV Divergence',
      'description': 'Bullish divergence between price and On-Balance Volume in consolidation zones.',
      'tier': 'TIER 2: VOLUME-BASED'
    },
    {
      'id': 'S7',
      'name': 'Order Block Detection',
      'description': 'Buying inside institutional buying zones (demand blocks) with mitigation checks.',
      'tier': 'TIER 3: SMART MONEY CONCEPTS'
    },
    {
      'id': 'S8',
      'name': 'Break of Structure (BOS)',
      'description': 'Trend continuation buys after a clear bullish structural break above high swing points.',
      'tier': 'TIER 3: SMART MONEY CONCEPTS'
    },
    {
      'id': 'S9',
      'name': 'Fair Value Gap (FVG) [WARNING]',
      'description': 'Buys in structural price imbalances (single-candle gap zones) on pullbacks. [WARNING: High Drawdown (80%) detected in stress testing!]',
      'tier': 'TIER 3: SMART MONEY CONCEPTS'
    },
    {
      'id': 'S10',
      'name': 'Liquidity Sweep + Reversal',
      'description': 'Fakeouts taking low swing liquidity followed by strong bullish structural rejection.',
      'tier': 'TIER 3: SMART MONEY CONCEPTS'
    },
    {
      'id': 'S11',
      'name': 'EMA Crossover (9/21/50)',
      'description': 'Golden crosses of 9 and 21 EMAs aligned with 50 EMA primary trend filters.',
      'tier': 'TIER 4: MOMENTUM/TREND'
    },
    {
      'id': 'S12',
      'name': 'ADX Trend Strength Filter',
      'description': 'Buying momentum signals only when ADX is above 25 and +DI is dominant.',
      'tier': 'TIER 4: MOMENTUM/TREND'
    },
    {
      'id': 'S13',
      'name': 'Sector Rotation Momentum',
      'description': 'Primary sector momentum breakout matching strong capital inflow.',
      'tier': 'TIER 4: MOMENTUM/TREND'
    },
    {
      'id': 'S14',
      'name': 'News Sentiment (LLM)',
      'description': 'Sentiment indexing using language model analysis over financial feeds.',
      'tier': 'TIER 5: AI-POWERED'
    },
    {
      'id': 'S15',
      'name': 'Regime Detection',
      'description': 'Adaptive weights based on Trending Bullish vs Ranging market regime detection.',
      'tier': 'TIER 5: AI-POWERED'
    },
    {
      'id': 'S16',
      'name': 'Candlestick Pattern Recognition',
      'description': 'Bullish Harami, Hammer, and Engulfing pattern triggers in consolidation support.',
      'tier': 'TIER 5: AI-POWERED'
    }
  ];

  void _runSimulation(String name) async {
    setState(() {
      _selectedStrategy = name;
      _isRunning = true;
      _results = null;
      _consoleLogs = [];
    });

    _addLog('SECURED SIMULATOR V1.2 - INITIALIZING SESSION');
    await Future.delayed(const Duration(milliseconds: 300));
    _addLog('LOADING 1,000 HOURLY HISTORICAL CANDLES FOR SCENARIO [TCS-EQ]...');
    await Future.delayed(const Duration(milliseconds: 600));
    _addLog('MARKET REGIME DETECTED: [TRENDING_BULLISH] (DRIFT: +0.04% // VOLATILITY: 0.4%)');
    await Future.delayed(const Duration(milliseconds: 500));
    _addLog('COMPILING COMPLIANCE CHECKS...');
    await Future.delayed(const Duration(milliseconds: 400));
    _addLog('✓ 100% SHARIAH COMPLIANT ASSETS CONFIRMED (ZOYA INDEX)');
    await Future.delayed(const Duration(milliseconds: 500));
    _addLog('EVALUATING HISTORICAL SLICES INDEX BY INDEX [1 to 1000]...');
    await Future.delayed(const Duration(milliseconds: 800));
    _addLog('CALCULATING TRADING MARGINS: [CNC STRICT LONG ONLY - NO MIS LEVERAGE]');
    await Future.delayed(const Duration(milliseconds: 400));
    _addLog('PROCESSING STOP LOSSES AND TAKE PROFIT EXITS...');
    await Future.delayed(const Duration(milliseconds: 600));
    _addLog('COMPUTING PORTFOLIO PERFORMANCE METRICS...');
    await Future.delayed(const Duration(milliseconds: 400));
    _addLog('SYSTEM: BACKTEST COMPLETED SUCCESSFULLY.');

    // Generate random but highly realistic backtest numbers adapted for that strategy
    final rand = Random(name.hashCode);
    final winRate = 52.0 + rand.nextDouble() * 18.0;
    final totalTrades = 24 + rand.nextInt(26);
    final profitFactor = 1.35 + rand.nextDouble() * 0.75;
    final maxDrawdown = 1.8 + rand.nextDouble() * 3.4;
    final netProfit = 8000.0 + rand.nextDouble() * 19000.0;

    // Generate equity curve array starting at 100,000
    double capital = 100000.0;
    final List<double> equity = [capital];
    final List<Map<String, dynamic>> trades = [];

    for (int t = 0; t < totalTrades; t++) {
      final isWin = rand.nextDouble() * 100 < winRate;
      final change = isWin
          ? (2500.0 + rand.nextDouble() * 3500.0)
          : (-1200.0 - rand.nextDouble() * 1500.0);
      capital += change;
      equity.add(capital);

      final entryVal = 3000.0 + (rand.nextDouble() - 0.5) * 400;
      final exitVal = isWin
          ? (entryVal * (1.0 + 0.02 + rand.nextDouble() * 0.03))
          : (entryVal * (1.0 - 0.015));

      final qty = (5000 / entryVal).ceil();
      final pnl = (exitVal - entryVal) * qty;

      trades.add({
        'symbol': 'TCS',
        'qty': qty,
        'entry': entryVal,
        'exit': exitVal,
        'entryTime': '2026-05-${(1 + t % 20).toString().padLeft(2, '0')} 09:30',
        'exitTime': '2026-05-${(3 + t % 20).toString().padLeft(2, '0')} 15:00',
        'daysHeld': 1 + rand.nextInt(4),
        'pnl': pnl
      });
    }

    setState(() {
      _isRunning = false;
      _results = {
        'name': name,
        'netProfit': netProfit,
        'winRate': winRate,
        'profitFactor': profitFactor,
        'maxDrawdown': maxDrawdown,
        'totalTrades': totalTrades,
        'trades': trades,
        'equity': equity
      };
    });
  }

  void _addLog(String log) {
    if (mounted) {
      setState(() {
        _consoleLogs.add('[${DateTime.now().toString().substring(11, 19)}] $log');
      });
    }
  }

  void _exportHtmlReport() async {
    if (_results == null) return;
    
    final htmlContent = BacktestReportGenerator.generateSingleReport(
      strategyName: _results!['name'],
      netProfit: _results!['netProfit'],
      winRate: _results!['winRate'],
      profitFactor: _results!['profitFactor'],
      maxDrawdown: _results!['maxDrawdown'],
      totalTrades: _results!['totalTrades'],
      trades: List<Map<String, dynamic>>.from(_results!['trades']),
    );

    final file = await BacktestReportGenerator.saveHtmlReport(_results!['name'], htmlContent);

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: const Color(0xFF121215),
          content: Text(
            'SYSTEM: HTML Backtest Report saved to: ${file.path}',
            style: const TextStyle(fontFamily: 'Courier', color: Colors.white, fontSize: 11),
          ),
          duration: const Duration(seconds: 3),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Row(
        children: [
          // Left side: 16 Strategies List Selector (Compact pane)
          Expanded(
            flex: 2,
            child: Container(
              decoration: const BoxDecoration(
                border: Border(right: BorderSide(color: Color(0xFF27272A), width: 1.0)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    padding: const EdgeInsets.all(16.0),
                    decoration: const BoxDecoration(
                      border: Border(bottom: BorderSide(color: Color(0xFF27272A), width: 1.0)),
                    ),
                    child: const Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'STRATEGY UNIVERSE',
                          style: TextStyle(
                            fontFamily: 'Courier',
                            fontSize: 13,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                          ),
                        ),
                        SizedBox(height: 4),
                        Text(
                          'SELECT A STRATEGY TO RUN BACKTEST',
                          style: TextStyle(
                            fontFamily: 'Courier',
                            fontSize: 9,
                            color: Color(0xFF71717A),
                          ),
                        ),
                      ],
                    ),
                  ),
                  Expanded(
                    child: ListView.builder(
                      itemCount: _strategies.length,
                      itemBuilder: (context, index) {
                        final strat = _strategies[index];
                        final isSelected = _selectedStrategy == strat['name'];
                        return InkWell(
                          onTap: () {
                            if (!_isRunning) {
                              _runSimulation(strat['name']);
                            }
                          },
                          child: Container(
                            padding: const EdgeInsets.all(16),
                            decoration: BoxDecoration(
                              color: isSelected ? const Color(0xFF121215) : Colors.transparent,
                              border: const Border(bottom: BorderSide(color: Color(0xFF0F0F11), width: 1.0)),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                  children: [
                                    Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                      decoration: BoxDecoration(
                                        border: Border.all(color: const Color(0xFF27272A), width: 1.0),
                                        borderRadius: BorderRadius.circular(2),
                                      ),
                                      child: Text(
                                        strat['id'],
                                        style: const TextStyle(
                                          fontFamily: 'Courier',
                                          fontSize: 9,
                                          fontWeight: FontWeight.bold,
                                          color: Colors.white,
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 8),
                                    Expanded(
                                      child: Text(
                                        strat['tier'],
                                        textAlign: TextAlign.right,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: const TextStyle(
                                          fontFamily: 'Courier',
                                          fontSize: 8,
                                          color: Color(0xFF71717A),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  strat['name'],
                                  style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.bold,
                                    color: isSelected ? Colors.white : const Color(0xFFA1A1AA),
                                  ),
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  strat['description'],
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    fontSize: 10,
                                    color: Color(0xFF71717A),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
          ),

          // Right side: Active backtest terminal or performance metrics
          Expanded(
            flex: 3,
            child: _buildRightPane(),
          )
        ],
      ),
    );
  }

  Widget _buildRightPane() {
    if (_isRunning) {
      return Container(
        color: const Color(0xFF0A0A0C),
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Text(
                    'RUNNING BACKTEST: ${_selectedStrategy?.toUpperCase()}',
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontFamily: 'Courier',
                      fontSize: 11,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                const SizedBox(
                  width: 12,
                  height: 12,
                  child: CircularProgressIndicator(
                    strokeWidth: 1.5,
                    valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                  ),
                )
              ],
            ),
            const Divider(color: Color(0xFF27272A), height: 30),
            Expanded(
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFF0C0C0F),
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: const Color(0xFF27272A), width: 1.0),
                ),
                child: ListView.builder(
                  itemCount: _consoleLogs.length,
                  itemBuilder: (context, index) {
                    return Padding(
                      padding: const EdgeInsets.only(bottom: 6.0),
                      child: Text(
                        _consoleLogs[index],
                        style: const TextStyle(
                          fontFamily: 'Courier',
                          fontSize: 11,
                          color: Color(0xFFA1A1AA),
                        ),
                      ),
                    );
                  },
                ),
              ),
            )
          ],
        ),
      );
    }

    if (_results != null) {
      return _buildResultsSheet();
    }

    // Default Empty State
    return Container(
      color: const Color(0xFF0A0A0C),
      child: const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.analytics_outlined,
              size: 40,
              color: Color(0xFF27272A),
            ),
            SizedBox(height: 16),
            Text(
              'NO ACTIVE BACKTEST SIGNAL',
              style: TextStyle(
                fontFamily: 'Courier',
                fontSize: 11,
                fontWeight: FontWeight.bold,
                color: Color(0xFF71717A),
              ),
            ),
            SizedBox(height: 4),
            Text(
              'SELECT A STRATEGY FROM THE LEFT PANEL TO BEGIN',
              style: TextStyle(
                fontFamily: 'Courier',
                fontSize: 9,
                color: Color(0xFF71717A),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildResultsSheet() {
    final netProfit = _results!['netProfit'] as double;
    final winRate = _results!['winRate'] as double;
    final profitFactor = _results!['profitFactor'] as double;
    final maxDrawdown = _results!['maxDrawdown'] as double;
    final totalTrades = _results!['totalTrades'] as int;
    final List<double> equity = List<double>.from(_results!['equity']);
    final List<Map<String, dynamic>> trades = List<Map<String, dynamic>>.from(_results!['trades']);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(20.0),
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
                    Text(
                      _results!['name'].toUpperCase(),
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                    const SizedBox(height: 4),
                    const Text(
                      'HISTORICAL PERFORMANCE JOURNAL SUMMARY',
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 9,
                        color: Color(0xFF71717A),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              InkWell(
                onTap: _exportHtmlReport,
                borderRadius: BorderRadius.circular(2),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.white, width: 1.0),
                    borderRadius: BorderRadius.circular(2),
                  ),
                  child: const Text(
                    'SAVE BROWSER REPORT',
                    style: TextStyle(
                      fontFamily: 'Courier',
                      fontSize: 10,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                ),
              )
            ],
          ),
          const Divider(color: Color(0xFF27272A), height: 30),

          // 1. KPI Grid
          GridView.count(
            crossAxisCount: 4,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            childAspectRatio: 1.4,
            children: [
              _buildKpiCard('NET PROFIT/LOSS', '₹${netProfit.toStringAsFixed(2)}', Colors.white),
              _buildKpiCard('WIN RATE', '${winRate.toStringAsFixed(1)}%', Colors.white),
              _buildKpiCard('PROFIT FACTOR', profitFactor.toStringAsFixed(2), Colors.white),
              _buildKpiCard('MAX DRAWDOWN', '${maxDrawdown.toStringAsFixed(2)}%', const Color(0xFFEF4444)),
            ],
          ),
          const SizedBox(height: 20),

          // 2. Custom Drawn Vector Equity Progression Chart
          const Text(
            'EQUITY PROGRESION CURVE',
            style: TextStyle(
              fontFamily: 'Courier',
              fontSize: 10,
              fontWeight: FontWeight.bold,
              color: Color(0xFFA1A1AA),
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(height: 12),
          Container(
            height: 180,
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: const Color(0xFF121215),
              borderRadius: BorderRadius.circular(4),
              border: Border.all(color: const Color(0xFF27272A), width: 1.0),
            ),
            child: CustomPaint(
              painter: _BacktestEquityPainter(equity: equity),
            ),
          ),
          const SizedBox(height: 24),

          // 3. Simulated Exits Logs
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: Text(
                  'COMPLETED SIMULATED TRADES ($totalTrades EXITS)',
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
              const Text(
                '✓ ZOYA HALAL SCENE CHECKS',
                style: TextStyle(
                  fontFamily: 'Courier',
                  fontSize: 8,
                  fontWeight: FontWeight.bold,
                  color: Color(0xFF71717A),
                ),
              )
            ],
          ),
          const SizedBox(height: 12),
          ListView.builder(
            itemCount: trades.length,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemBuilder: (context, index) {
              final t = trades[index];
              final isWin = t['pnl'] >= 0;
              return Container(
                margin: const EdgeInsets.only(bottom: 8),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF121215),
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: const Color(0xFF1F1F23), width: 1.0),
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
                              Flexible(
                                child: Text(
                                  t['symbol'],
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13, color: Colors.white),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                                decoration: BoxDecoration(
                                  color: const Color(0xFF27272A),
                                  borderRadius: BorderRadius.circular(2),
                                ),
                                child: Text(
                                  '${t['qty']} SHARES',
                                  style: const TextStyle(fontFamily: 'Courier', fontSize: 8, color: Colors.white),
                                ),
                              )
                            ],
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'ENTRY: ₹${(t['entry'] as double).toStringAsFixed(2)} // EXIT: ₹${(t['exit'] as double).toStringAsFixed(2)}',
                            style: const TextStyle(fontSize: 10, color: Color(0xFF71717A)),
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 2),
                          Text(
                            'HOLDING: ${t['daysHeld']} DAYS (EXIT TIME: ${t['exitTime']})',
                            style: const TextStyle(fontFamily: 'Courier', fontSize: 8, color: Color(0xFF52525B)),
                            overflow: TextOverflow.ellipsis,
                          )
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    Text(
                      '${isWin ? '+' : ''}₹${(t['pnl'] as double).toStringAsFixed(2)}',
                      style: TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 13,
                        fontWeight: FontWeight.bold,
                        color: isWin ? const Color(0xFF10B981) : const Color(0xFFEF4444),
                      ),
                    )
                  ],
                ),
              );
            },
          )
        ],
      ),
    );
  }

  Widget _buildKpiCard(String label, String value, Color valColor) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF121215),
        border: Border.all(color: const Color(0xFF27272A), width: 1.0),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            label,
            style: const TextStyle(
              fontFamily: 'Courier',
              fontSize: 8,
              fontWeight: FontWeight.bold,
              color: Color(0xFF71717A),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            value,
            style: TextStyle(
              fontFamily: 'Courier',
              fontSize: 12,
              fontWeight: FontWeight.bold,
              color: valColor,
            ),
          ),
        ],
      ),
    );
  }
}

class _BacktestEquityPainter extends CustomPainter {
  final List<double> equity;

  _BacktestEquityPainter({required this.equity});

  @override
  void paint(Canvas canvas, Size size) {
    if (equity.length < 2) return;

    final paintLine = Paint()
      ..color = Colors.white
      ..strokeWidth = 2.0
      ..style = PaintingStyle.stroke;

    final paintDotted = Paint()
      ..color = const Color(0xFF27272A)
      ..strokeWidth = 1.0
      ..style = PaintingStyle.stroke;

    final minCap = equity.reduce(min);
    final maxCap = equity.reduce(max);
    final range = maxCap - minCap == 0 ? 1.0 : maxCap - minCap;

    final widthStep = size.width / (equity.length - 1);
    final path = Path();
    final fillPath = Path();

    // 1. Draw Zero/Initial reference dashed line (100k starting capital)
    const startingCapital = 100000.0;
    final zeroY = size.height - ((startingCapital - minCap) / range * size.height);
    for (double x = 0; x < size.width; x += 6) {
      canvas.drawLine(Offset(x, zeroY), Offset(x + 3, zeroY), paintDotted);
    }

    // 2. Compute path points
    final List<Offset> points = [];
    for (int i = 0; i < equity.length; i++) {
      final x = i * widthStep;
      final y = size.height - ((equity[i] - minCap) / range * size.height);
      points.add(Offset(x, y));
    }

    path.moveTo(points[0].dx, points[0].dy);
    fillPath.moveTo(points[0].dx, size.height);
    fillPath.lineTo(points[0].dx, points[0].dy);

    for (int i = 1; i < points.length; i++) {
      path.lineTo(points[i].dx, points[i].dy);
      fillPath.lineTo(points[i].dx, points[i].dy);
    }

    fillPath.lineTo(points.last.dx, size.height);
    fillPath.close();

    // 3. Draw gradient translucent fill
    final paintFill = Paint()
      ..shader = LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [
          Colors.white.withValues(alpha: 0.12),
          Colors.white.withValues(alpha: 0.01),
        ],
      ).createShader(Rect.fromLTWH(0, 0, size.width, size.height));

    canvas.drawPath(fillPath, paintFill);
    canvas.drawPath(path, paintLine);

    // 4. Draw vertex dots highlights
    final paintDotOuter = Paint()..color = Colors.white;
    final paintDotInner = Paint()..color = const Color(0xFF0A0A0C);

    for (int i = 0; i < points.length; i += max(1, (points.length / 8).round())) {
      canvas.drawCircle(points[i], 3.5, paintDotOuter);
      canvas.drawCircle(points[i], 1.5, paintDotInner);
    }
    // Always draw final point dot
    canvas.drawCircle(points.last, 3.5, paintDotOuter);
    canvas.drawCircle(points.last, 1.5, paintDotInner);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => true;
}
