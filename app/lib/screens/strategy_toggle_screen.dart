import 'package:flutter/material.dart';
import '../utils/portfolio_risk_engine.dart';

class StrategyToggleScreen extends StatefulWidget {
  const StrategyToggleScreen({super.key});

  @override
  State<StrategyToggleScreen> createState() => _StrategyToggleScreenState();
}

class _StrategyToggleScreenState extends State<StrategyToggleScreen> {
  // 16 strategies, their parameters, and mock performance metrics
  final Map<String, List<Map<String, dynamic>>> _strategyTiers = {
    'TIER 1: PRICE ACTION FOUNDATION': [
      {
        'id': 'S1',
        'name': 'Opening Range Breakout (ORB)',
        'active': true,
        'parameters': 'Timeframe: 15m, Volume Filter: 1.3x',
        'winRate': 65.4,
        'trades': 26,
        'profitFactor': 1.85,
        'netProfit': 4350.00,
        'history': [
          {'symbol': 'TCS', 'pnl': 720.00, 'win': true, 'time': 'Today'},
          {'symbol': 'INFY', 'pnl': -340.00, 'win': false, 'time': 'Yesterday'},
          {'symbol': 'WIPRO', 'pnl': 510.00, 'win': true, 'time': '2 days ago'}
        ]
      },
      {
        'id': 'S2',
        'name': 'Box Theory (Darvas Box)',
        'active': true,
        'parameters': 'Period: 20d, Box limit: <5%',
        'winRate': 78.5,
        'trades': 14,
        'profitFactor': 2.65,
        'netProfit': 8750.00,
        'history': [
          {'symbol': 'INFY', 'pnl': 1250.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'TCS', 'pnl': 2400.00, 'win': true, 'time': '4 days ago'},
          {'symbol': 'WIPRO', 'pnl': -450.00, 'win': false, 'time': '1 week ago'}
        ]
      },
      {
        'id': 'S3',
        'name': 'Support/Resistance Flip',
        'active': true,
        'parameters': 'Period: 30d, Re-test zone: 0.5%',
        'winRate': 62.0,
        'trades': 18,
        'profitFactor': 1.45,
        'netProfit': 2100.00,
        'history': [
          {'symbol': 'TCS', 'pnl': -480.00, 'win': false, 'time': 'Yesterday'},
          {'symbol': 'WIPRO', 'pnl': 680.00, 'win': true, 'time': '3 days ago'},
          {'symbol': 'INFY', 'pnl': 340.00, 'win': true, 'time': '5 days ago'}
        ]
      },
    ],
    'TIER 2: VOLUME-BASED': [
      {
        'id': 'S4',
        'name': 'Volume Profile (POC, VAH, VAL)',
        'active': false,
        'parameters': 'Bins: 30, Value Area: 70%',
        'winRate': 72.3,
        'trades': 22,
        'profitFactor': 2.10,
        'netProfit': 6500.00,
        'history': [
          {'symbol': 'WIPRO', 'pnl': -300.00, 'win': false, 'time': 'Today'},
          {'symbol': 'TCS', 'pnl': 1450.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'INFY', 'pnl': 890.00, 'win': true, 'time': '3 days ago'}
        ]
      },
      {
        'id': 'S5',
        'name': 'VWAP + Deviation Bands',
        'active': true,
        'parameters': 'Period: Daily, Bands: ±2.0',
        'winRate': 60.5,
        'trades': 38,
        'profitFactor': 1.55,
        'netProfit': 3450.00,
        'history': [
          {'symbol': 'INFY', 'pnl': -250.00, 'win': false, 'time': 'Today'},
          {'symbol': 'WIPRO', 'pnl': 430.00, 'win': true, 'time': 'Today'},
          {'symbol': 'TCS', 'pnl': 680.00, 'win': true, 'time': 'Yesterday'}
        ]
      },
      {
        'id': 'S6',
        'name': 'OBV Divergence',
        'active': false,
        'parameters': 'Lookback: 20, Filter: Bullish only',
        'winRate': 58.0,
        'trades': 12,
        'profitFactor': 1.30,
        'netProfit': 1200.00,
        'history': [
          {'symbol': 'WIPRO', 'pnl': 580.00, 'win': true, 'time': '2 days ago'},
          {'symbol': 'TCS', 'pnl': -320.00, 'win': false, 'time': '1 week ago'},
          {'symbol': 'INFY', 'pnl': 210.00, 'win': true, 'time': '2 weeks ago'}
        ]
      },
    ],
    'TIER 3: SMART MONEY CONCEPTS (SMC)': [
      {
        'id': 'S7',
        'name': 'Order Block Detection',
        'active': true,
        'parameters': 'Scans: 5, OB Breakout: 1.5x',
        'winRate': 81.2,
        'trades': 16,
        'profitFactor': 3.10,
        'netProfit': 11400.00,
        'history': [
          {'symbol': 'TCS', 'pnl': 1800.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'INFY', 'pnl': 2250.00, 'win': true, 'time': '3 days ago'},
          {'symbol': 'WIPRO', 'pnl': -540.00, 'win': false, 'time': '5 days ago'}
        ]
      },
      {
        'id': 'S8',
        'name': 'Break of Structure (BOS)',
        'active': true,
        'parameters': 'Lookback: 20, Filter: Uptrend',
        'winRate': 68.0,
        'trades': 25,
        'profitFactor': 1.95,
        'netProfit': 5600.00,
        'history': [
          {'symbol': 'TCS', 'pnl': 1250.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'INFY', 'pnl': -450.00, 'win': false, 'time': '2 days ago'},
          {'symbol': 'WIPRO', 'pnl': 890.00, 'win': true, 'time': '4 days ago'}
        ]
      },
      {
        'id': 'S9',
        'name': 'Fair Value Gap (FVG)',
        'active': false,
        'parameters': 'Window: 3 candles, Gap fill: Buy',
        'winRate': 70.4,
        'trades': 27,
        'profitFactor': 2.05,
        'netProfit': 7200.00,
        'history': [
          {'symbol': 'INFY', 'pnl': 940.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'WIPRO', 'pnl': -380.00, 'win': false, 'time': '3 days ago'},
          {'symbol': 'TCS', 'pnl': 1480.00, 'win': true, 'time': '6 days ago'}
        ]
      },
      {
        'id': 'S10',
        'name': 'Liquidity Sweep + Reversal',
        'active': true,
        'parameters': 'Lookback: 15, Sweep target: Lows',
        'winRate': 75.0,
        'trades': 20,
        'profitFactor': 2.40,
        'netProfit': 6850.00,
        'history': [
          {'symbol': 'INFY', 'pnl': 1200.00, 'win': true, 'time': 'Today'},
          {'symbol': 'TCS', 'pnl': -490.00, 'win': false, 'time': 'Yesterday'},
          {'symbol': 'WIPRO', 'pnl': 1140.00, 'win': true, 'time': '3 days ago'}
        ]
      },
    ],
    'TIER 4: MOMENTUM/TREND': [
      {
        'id': 'S11',
        'name': 'EMA Crossover (9/21/50)',
        'active': true,
        'parameters': 'Periods: 9/21/50, Golden Cross',
        'winRate': 66.7,
        'trades': 30,
        'profitFactor': 1.80,
        'netProfit': 5250.00,
        'history': [
          {'symbol': 'WIPRO', 'pnl': 650.00, 'win': true, 'time': 'Today'},
          {'symbol': 'TCS', 'pnl': -480.00, 'win': false, 'time': 'Yesterday'},
          {'symbol': 'INFY', 'pnl': 920.00, 'win': true, 'time': '3 days ago'}
        ]
      },
      {
        'id': 'S12',
        'name': 'ADX Trend Strength Filter',
        'active': true,
        'parameters': 'Period: 14, ADX Cap: >25',
        'winRate': 64.0,
        'trades': 25,
        'profitFactor': 1.65,
        'netProfit': 3800.00,
        'history': [
          {'symbol': 'INFY', 'pnl': 450.00, 'win': true, 'time': '2 days ago'},
          {'symbol': 'TCS', 'pnl': -380.00, 'win': false, 'time': '4 days ago'},
          {'symbol': 'WIPRO', 'pnl': 690.00, 'win': true, 'time': '1 week ago'}
        ]
      },
      {
        'id': 'S13',
        'name': 'Sector Rotation Momentum',
        'active': false,
        'parameters': 'Period: Daily, Cap: >1.05',
        'winRate': 55.5,
        'trades': 18,
        'profitFactor': 1.25,
        'netProfit': 950.00,
        'history': [
          {'symbol': 'WIPRO', 'pnl': -210.00, 'win': false, 'time': 'Today'},
          {'symbol': 'TCS', 'pnl': 450.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'INFY', 'pnl': 120.00, 'win': true, 'time': '4 days ago'}
        ]
      },
    ],
    'TIER 5: AI-POWERED': [
      {
        'id': 'S14',
        'name': 'News Sentiment (LLM)',
        'active': false,
        'parameters': 'Model: Gemini Mini, Limit: >0.6',
        'winRate': 70.0,
        'trades': 10,
        'profitFactor': 2.10,
        'netProfit': 3200.00,
        'history': [
          {'symbol': 'TCS', 'pnl': 890.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'WIPRO', 'pnl': -320.00, 'win': false, 'time': '3 days ago'},
          {'symbol': 'INFY', 'pnl': 950.00, 'win': true, 'time': '1 week ago'}
        ]
      },
      {
        'id': 'S15',
        'name': 'Regime Detection (ML)',
        'active': true,
        'parameters': 'Period: 20d, Feature: ATR/ADX',
        'winRate': 74.0,
        'trades': 19,
        'profitFactor': 2.30,
        'netProfit': 5900.00,
        'history': [
          {'symbol': 'INFY', 'pnl': 1120.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'TCS', 'pnl': -430.00, 'win': false, 'time': '2 days ago'},
          {'symbol': 'WIPRO', 'pnl': 940.00, 'win': true, 'time': '5 days ago'}
        ]
      },
      {
        'id': 'S16',
        'name': 'Pattern Recognition (CV)',
        'active': true,
        'parameters': 'Lookback: 30, Flag: 3% Pole',
        'winRate': 71.5,
        'trades': 21,
        'profitFactor': 2.15,
        'netProfit': 6200.00,
        'history': [
          {'symbol': 'WIPRO', 'pnl': 740.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'TCS', 'pnl': 1120.00, 'win': true, 'time': 'Yesterday'},
          {'symbol': 'INFY', 'pnl': -390.00, 'win': false, 'time': '3 days ago'}
        ]
      },
    ]
  };

  PortfolioStatus? _portfolioStatus;
  bool _isLoadingStatus = true;

  @override
  void initState() {
    super.initState();
    _loadPortfolioStatus();
  }

  Future<void> _loadPortfolioStatus() async {
    setState(() {
      _isLoadingStatus = true;
    });
    try {
      final status = await PortfolioRiskEngine.getPortfolioStatus();
      if (mounted) {
        setState(() {
          _portfolioStatus = status;
          _isLoadingStatus = false;
          // Apply safeguard: if safeguard is active, turn off non-compliant strategies
          if (status.isSafeguardActive) {
            for (var list in _strategyTiers.values) {
              for (var strat in list) {
                final double winRate = (strat['winRate'] as num?)?.toDouble() ?? 0.0;
                if (winRate < 70.0) {
                  strat['active'] = false;
                }
              }
            }
          }
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isLoadingStatus = false;
        });
      }
    }
  }

  void _showStrategyReport(Map<String, dynamic> strategy) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF0A0A0C),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(8)),
        side: BorderSide(color: Color(0xFF27272A), width: 1.0),
      ),
      builder: (context) {
        int activeTab = 0; // 0: Performance, 1: Logic Rules

        return StatefulBuilder(
          builder: (context, setModalState) {
            final double winRate = strategy['winRate'];
            final List<Map<String, dynamic>> history = strategy['history'];

            // Compute cumulative equity curve data from exit history
            List<double> equityPoints = [0.0];
            double runningSum = 0.0;
            for (var trade in history.reversed) {
              runningSum += trade['pnl'];
              equityPoints.add(runningSum);
            }

            return DraggableScrollableSheet(
              initialChildSize: 0.7,
              maxChildSize: 0.9,
              minChildSize: 0.5,
              expand: false,
              builder: (context, scrollController) {
                return SingleChildScrollView(
                  controller: scrollController,
                  padding: const EdgeInsets.all(24.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // Header
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            '${strategy['id']} // REPORT CARD',
                            style: const TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: Color(0xFFA1A1AA),
                              letterSpacing: 1.0,
                            ),
                          ),
                          GestureDetector(
                            onTap: () => Navigator.pop(context),
                            child: const Icon(Icons.close, color: Colors.white, size: 20),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        strategy['name'],
                        style: const TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                      ),
                      if (strategy['id'] == 'S4') ...[
                        const SizedBox(height: 12),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: const Color(0xFF2D1010),
                            border: Border.all(color: const Color(0xFFEF4444), width: 1.0),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: const Text(
                            'STATUS: DEACTIVATED\nReason: Consistent negative expectancy in stress testing. Do not activate in live portfolios.',
                            style: TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 11,
                              fontWeight: FontWeight.bold,
                              color: Color(0xFFFCA5A5),
                              height: 1.4,
                            ),
                          ),
                        ),
                      ],
                      if (strategy['id'] == 'S9') ...[
                        const SizedBox(height: 12),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: const Color(0xFF2D2210),
                            border: Border.all(color: const Color(0xFFF59E0B), width: 1.0),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: const Text(
                            'WARNING: HIGH DRAWDOWN REGIME DETECTED\nStress testing reveals up to 80% maximum drawdown. Use with extreme caution and custom stop-losses.',
                            style: TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 11,
                              fontWeight: FontWeight.bold,
                              color: Color(0xFFFDE68A),
                              height: 1.4,
                            ),
                          ),
                        ),
                      ],
                      const SizedBox(height: 16),

                      // Tabs control (Monochrome design)
                      Row(
                        children: [
                          Expanded(
                            child: GestureDetector(
                              onTap: () => setModalState(() => activeTab = 0),
                              child: Container(
                                padding: const EdgeInsets.symmetric(vertical: 8),
                                decoration: BoxDecoration(
                                  border: Border(
                                    bottom: BorderSide(
                                      color: activeTab == 0 ? Colors.white : Colors.transparent,
                                      width: 2.0,
                                    ),
                                  ),
                                ),
                                alignment: Alignment.center,
                                child: Text(
                                  'PERFORMANCE STATS',
                                  style: TextStyle(
                                    fontFamily: 'Courier',
                                    fontSize: 11,
                                    fontWeight: FontWeight.bold,
                                    color: activeTab == 0 ? Colors.white : const Color(0xFF71717A),
                                  ),
                                ),
                              ),
                            ),
                          ),
                          Expanded(
                            child: GestureDetector(
                              onTap: () => setModalState(() => activeTab = 1),
                              child: Container(
                                padding: const EdgeInsets.symmetric(vertical: 8),
                                decoration: BoxDecoration(
                                  border: Border(
                                    bottom: BorderSide(
                                      color: activeTab == 1 ? Colors.white : Colors.transparent,
                                      width: 2.0,
                                    ),
                                  ),
                                ),
                                alignment: Alignment.center,
                                child: Text(
                                  'ALGORITHMIC LOGIC',
                                  style: TextStyle(
                                    fontFamily: 'Courier',
                                    fontSize: 11,
                                    fontWeight: FontWeight.bold,
                                    color: activeTab == 1 ? Colors.white : const Color(0xFF71717A),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const Divider(color: Color(0xFF27272A), height: 1),
                      const SizedBox(height: 20),

                      if (activeTab == 0) ...[
                        // 1. Performance View

                        // Cumulative Equity Sparkline Canvas
                        const Text(
                          'CUMULATIVE PERFORMANCE SPARKLINE',
                          style: TextStyle(fontFamily: 'Courier', fontSize: 10, color: Color(0xFF71717A), letterSpacing: 0.5),
                        ),
                        const SizedBox(height: 8),
                        Container(
                          width: double.infinity,
                          height: 100,
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          decoration: BoxDecoration(
                            color: const Color(0xFF121215),
                            border: Border.all(color: const Color(0xFF27272A), width: 1.0),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: CustomPaint(
                            painter: _EquityCurvePainter(equityPoints),
                          ),
                        ),
                        const SizedBox(height: 20),

                        // Strategy Parameters Box
                        const Text(
                          'STRATEGIC TUNING PARAMETERS',
                          style: TextStyle(fontFamily: 'Courier', fontSize: 10, color: Color(0xFF71717A), letterSpacing: 0.5),
                        ),
                        const SizedBox(height: 6),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: const Color(0xFF121215),
                            border: Border.all(color: const Color(0xFF27272A), width: 1.0),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            strategy['parameters'],
                            style: const TextStyle(fontFamily: 'Courier', fontSize: 12, color: Colors.white),
                          ),
                        ),
                        const SizedBox(height: 20),

                        // Performance Metrics Grid
                        const Text(
                          'HISTORICAL PERFORMANCE STATS',
                          style: TextStyle(fontFamily: 'Courier', fontSize: 10, color: Color(0xFF71717A), letterSpacing: 0.5),
                        ),
                        const SizedBox(height: 8),
                        
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            const Text(
                              'WIN RATE',
                              style: TextStyle(fontFamily: 'Courier', fontSize: 12, color: Color(0xFFA1A1AA)),
                            ),
                            Text(
                              '${winRate.toStringAsFixed(1)}%',
                              style: const TextStyle(fontFamily: 'Courier', fontSize: 14, fontWeight: FontWeight.bold, color: Colors.white),
                            ),
                          ],
                        ),
                        const SizedBox(height: 6),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(2),
                          child: LinearProgressIndicator(
                            value: winRate / 100.0,
                            backgroundColor: const Color(0xFF27272A),
                            valueColor: const AlwaysStoppedAnimation<Color>(Colors.white),
                            minHeight: 8,
                          ),
                        ),
                        const SizedBox(height: 16),

                        Row(
                          children: [
                            Expanded(
                              child: _buildModalStat('TOTAL TRADES', '${strategy['trades']} EXPOSURES'),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: _buildModalStat('PROFIT FACTOR', '${strategy['profitFactor']}'),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        _buildModalStat('NET PROFITS', '₹${strategy['netProfit'].toStringAsFixed(2)}', isNet: true),

                        const SizedBox(height: 24),

                        // Completed Trades list
                        const Text(
                          'RECENT EXITS',
                          style: TextStyle(fontFamily: 'Courier', fontSize: 10, color: Color(0xFF71717A), letterSpacing: 0.5),
                        ),
                        const SizedBox(height: 8),
                        
                        ListView.separated(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          itemCount: history.length,
                          separatorBuilder: (context, index) => const SizedBox(height: 8),
                          itemBuilder: (context, index) {
                            final item = history[index];
                            final isWin = item['win'] as bool;
                            
                            return Container(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                              decoration: BoxDecoration(
                                color: const Color(0xFF121215),
                                border: Border.all(color: const Color(0xFF27272A), width: 1.0),
                                borderRadius: BorderRadius.circular(4),
                              ),
                              child: Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  Row(
                                    children: [
                                      Text(
                                        item['symbol'],
                                        style: const TextStyle(fontFamily: 'Courier', fontSize: 14, fontWeight: FontWeight.bold, color: Colors.white),
                                      ),
                                      const SizedBox(width: 8),
                                      Text(
                                        item['time'],
                                        style: const TextStyle(fontSize: 11, color: Color(0xFF71717A)),
                                      ),
                                    ],
                                  ),
                                  Text(
                                    '${isWin ? "+" : ""}₹${item['pnl'].toStringAsFixed(2)}',
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
                        ),
                      ] else ...[
                        // 2. Algorithmic Logic Code Block View
                        const Text(
                          'MATHEMATICAL CONSTRAINTS & TRIGGERS',
                          style: TextStyle(fontFamily: 'Courier', fontSize: 10, color: Color(0xFF71717A), letterSpacing: 0.5),
                        ),
                        const SizedBox(height: 8),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: const Color(0xFF121215),
                            border: Border.all(color: const Color(0xFF27272A), width: 1.0),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            _getStrategyLogicPseudocode(strategy['id'], strategy['name']),
                            style: const TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 11,
                              color: Colors.white,
                              height: 1.4,
                            ),
                          ),
                        ),
                        const SizedBox(height: 20),
                        const Text(
                          'SHARIAH COMPLIANCE SANCTIONS',
                          style: TextStyle(fontFamily: 'Courier', fontSize: 10, color: Color(0xFF71717A), letterSpacing: 0.5),
                        ),
                        const SizedBox(height: 8),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: const Color(0xFF121215),
                            border: Border.all(color: const Color(0xFF450A0A), width: 1.0),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: const Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'STRICT COMPLIANCE GUARD ACTIVE',
                                style: TextStyle(
                                  fontFamily: 'Courier',
                                  fontSize: 11,
                                  fontWeight: FontWeight.bold,
                                  color: Color(0xFFEF4444),
                                ),
                              ),
                              SizedBox(height: 6),
                              Text(
                                '1. Strictly long-only execution (no short selling allowed).\n2. Enforced 100% Cash Delivery basis (no MIS leverage/intraday margin).\n3. O(1) checking against Zoya Sandbox halal stock directory prior to order routing.',
                                style: TextStyle(
                                  fontSize: 11,
                                  color: Color(0xFFA1A1AA),
                                  height: 1.4,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ],
                  ),
                );
              },
            );
          },
        );
      },
    );
  }

  // Returns beautiful quant pseudocode rules for each strategy
  String _getStrategyLogicPseudocode(String id, String name) {
    switch (id) {
      case 'S1':
        return """// OPENING RANGE BREAKOUT (ORB) RULES
Range_High = MAX(High in first 15 mins)
Range_Low  = MIN(Low in first 15 mins)

IF (Close crosses above Range_High) {
    Signal = BUY
    Price = Close
    StopLoss = Range_Low
    Target = Price + 2 * (Price - StopLoss)
}""";
      case 'S2':
        return """// DARVAS BOX THEORY RULES
Period = 20 Days
Box_High = MAX(High over Period)

IF (High reaches Box_High) {
    // Probe bottom barrier
    Box_Low = MIN(Lows where High < Box_High)
    IF (Close crosses above Box_High) {
        Signal = BUY
        StopLoss = Box_Low
        Target = Box_High + 2 * (Box_High - Box_Low)
    }
}""";
      case 'S3':
        return """// SUPPORT/RESISTANCE FLIP RULES
Level = Identify Historical Pivot Peak (30d)
ReTest_Zone = Level * 0.005 // 0.5% tolerance

IF (Price falls into [Level - ReTest_Zone, Level + ReTest_Zone]) {
    IF (HammerCandlePattern && Volume > 1.2 * SMA(Volume, 20)) {
        Signal = BUY
        StopLoss = Low of current candle
        Target = Price + 3.0 * (Price - StopLoss)
    }
}""";
      case 'S4':
        return """// VOLUME PROFILE POC/VAL/VAH RULES
POC = Point of Control (Highest Volume node)
VAL = Value Area Low (Bottom 70% threshold)

IF (Price hits VAL from above) {
    IF (VolumeDelta > 0 && RSI < 35) {
        Signal = BUY
        StopLoss = VAL - (POC * 0.01)
        Target = POC // Exit at POC center
    }
}""";
      case 'S5':
        return """// VWAP DEVIATION BANDS RULES
VWAP = Sum(Volume * Price) / Sum(Volume)
LowerBand = VWAP - 2.0 * StdDev(Price)

IF (Low <= LowerBand) {
    IF (Close > Open) { // Bullish reversal
        Signal = BUY
        StopLoss = Low
        Target = VWAP
    }
}""";
      case 'S7':
        return """// ORDER BLOCK DETECTION RULES
Scan_Size = 5 last candles
IF (AggressiveBullishCandle) {
    OrderBlock = Last bearish candle's range
    IF (Price returns to OrderBlock) {
        Signal = BUY
        StopLoss = Bottom of OrderBlock
        Target = StopLoss + 3 * OB_Range
    }
}""";
      default:
        return """// ALGORITHMIC RULES FOR $id ($name)
Signal = BUY // Long-only Delivery

Trigger_Criteria:
- Score >= 7 / 10 Confluence
- Volatility ATR < 2.5%
- Double-checked against Shariah Filter

Risk:
- 1:2 Minimum Risk-to-Reward Ratio
- Hard Stop Loss calculated at nearest support""";
    }
  }

  Widget _buildModalStat(String label, String value, {bool isNet = false}) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF121215),
        border: Border.all(color: const Color(0xFF27272A), width: 1.0),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: const TextStyle(fontFamily: 'Courier', fontSize: 9, color: Color(0xFF71717A)),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: TextStyle(
              fontFamily: 'Courier',
              fontSize: 14,
              fontWeight: FontWeight.bold,
              color: isNet ? const Color(0xFF10B981) : Colors.white,
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                'STRATEGY SELECTION SWITCHBOARD',
                style: TextStyle(
                  fontFamily: 'Courier',
                  fontSize: 13,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                  letterSpacing: 1.0,
                ),
              ),
              if (_isLoadingStatus)
                const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 1.5, valueColor: AlwaysStoppedAnimation<Color>(Colors.white)),
                )
              else
                IconButton(
                  icon: const Icon(Icons.refresh, size: 18, color: Colors.white),
                  onPressed: _loadPortfolioStatus,
                  tooltip: 'Sync Portfolio Sizing',
                ),
            ],
          ),
          const SizedBox(height: 16),

          if (_portfolioStatus != null && _portfolioStatus!.isSafeguardActive) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF2C1515), // Deep subtle crimson
                border: Border.all(color: const Color(0xFFEF4444), width: 1.0),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.security, color: Color(0xFFFCA5A5), size: 18),
                      const SizedBox(width: 8),
                      const Text(
                        '🔒 PORTFOLIO SAFEGUARD ACTIVE',
                        style: TextStyle(
                          fontFamily: 'Courier',
                          fontSize: 13,
                          fontWeight: FontWeight.bold,
                          color: Color(0xFFFCA5A5),
                        ),
                      ),
                      const Spacer(),
                      Text(
                        'VALUE: ₹${_portfolioStatus!.totalPortfolioValue.toStringAsFixed(2)}',
                        style: const TextStyle(
                          fontFamily: 'Courier',
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                          color: Color(0xFFFCA5A5),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Total live portfolio value is below ₹100,000.00. To protect your capital, the system has locked all high-drawdown and low win-rate strategies (< 70% win rate). Only low risk, high probability strategies are active.',
                    style: TextStyle(
                      fontSize: 11,
                      color: Color(0xFFFCA5A5),
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
          ],
          if (_portfolioStatus != null && !_portfolioStatus!.isSafeguardActive) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF152C1E), // Deep subtle emerald
                border: Border.all(color: const Color(0xFF10B981), width: 1.0),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Row(
                children: [
                  const Icon(Icons.verified_user, color: Color(0xFFA7F3D0), size: 18),
                  const SizedBox(width: 8),
                  const Text(
                    '✓ FULL PORTFOLIO MODE UNLOCKED',
                    style: TextStyle(
                      fontFamily: 'Courier',
                      fontSize: 13,
                      fontWeight: FontWeight.bold,
                      color: Color(0xFFA7F3D0),
                    ),
                  ),
                  const Spacer(),
                  Text(
                    '₹${_portfolioStatus!.totalPortfolioValue.toStringAsFixed(2)}',
                    style: const TextStyle(
                      fontFamily: 'Courier',
                      fontSize: 11,
                      fontWeight: FontWeight.bold,
                      color: Color(0xFFA7F3D0),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
          ],

          // Tiers loops
          ..._strategyTiers.keys.map((tierName) {
            final list = _strategyTiers[tierName]!;
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8.0),
                  child: Text(
                    tierName,
                    style: const TextStyle(
                      fontFamily: 'Courier',
                      fontSize: 11,
                      fontWeight: FontWeight.bold,
                      color: Color(0xFFA1A1AA),
                      letterSpacing: 0.5,
                    ),
                  ),
                ),
                Container(
                  decoration: BoxDecoration(
                    color: const Color(0xFF121215),
                    border: Border.all(color: const Color(0xFF27272A), width: 1.0),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: ListView.separated(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    itemCount: list.length,
                    separatorBuilder: (context, index) => const Divider(color: Color(0xFF27272A), height: 1),
                    itemBuilder: (context, index) {
                      final strat = list[index];
                      final double winRate = (strat['winRate'] as num?)?.toDouble() ?? 0.0;
                      final bool isLocked = _portfolioStatus != null && 
                                           _portfolioStatus!.isSafeguardActive && 
                                           winRate < 70.0;
                      return ListTile(
                        leading: Container(
                          width: 24,
                          alignment: Alignment.center,
                          child: Text(
                            strat['id'],
                            style: TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 11,
                              fontWeight: FontWeight.bold,
                              color: isLocked ? const Color(0xFFEF4444) : const Color(0xFF71717A),
                            ),
                          ),
                        ),
                        title: Text(
                          strat['name'],
                          style: TextStyle(
                            fontSize: 13, 
                            color: isLocked ? const Color(0xFFA1A1AA) : Colors.white,
                            decoration: isLocked ? TextDecoration.lineThrough : null,
                          ),
                        ),
                        subtitle: _buildStrategySubtitle(strat),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            // Beautiful report icon button
                            IconButton(
                              icon: const Icon(Icons.analytics_outlined, size: 18, color: Color(0xFFA1A1AA)),
                              onPressed: () => _showStrategyReport(strat),
                              tooltip: 'View Report Card',
                            ),
                            Switch(
                              value: isLocked ? false : strat['active'],
                              onChanged: isLocked ? null : (val) {
                                setState(() {
                                  strat['active'] = val;
                                });
                              },
                              activeThumbColor: Colors.white,
                              activeTrackColor: const Color(0xFF27272A),
                              inactiveThumbColor: const Color(0xFF71717A),
                              inactiveTrackColor: const Color(0xFF0F0F11),
                            ),
                          ],
                        ),
                        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 0),
                      );
                    },
                  ),
                ),
                const SizedBox(height: 16),
              ],
            );
          }),
        ],
      ),
    );
  }

  Widget? _buildStrategySubtitle(Map<String, dynamic> strat) {
    final String id = strat['id'];
    final double winRate = (strat['winRate'] as num?)?.toDouble() ?? 0.0;
    final bool isLocked = _portfolioStatus != null && 
                         _portfolioStatus!.isSafeguardActive && 
                         winRate < 70.0;

    if (isLocked) {
      return Text(
        '🔒 LOCKED // Safeguard Active (Win Rate: ${winRate.toStringAsFixed(1)}% < 70%)',
        style: const TextStyle(
          color: Color(0xFFEF4444),
          fontSize: 10,
          fontFamily: 'Courier',
          fontWeight: FontWeight.bold,
        ),
      );
    }

    if (id == 'S4') {
      return const Text(
        'DEACTIVATED // Consistent negative expectancy in stress testing.',
        style: TextStyle(
          color: Color(0xFFEF4444),
          fontSize: 10,
          fontFamily: 'Courier',
          fontWeight: FontWeight.bold,
        ),
      );
    }
    if (id == 'S9') {
      return const Text(
        '[WARNING] HIGH DRAWDOWN // 80% stress test drawdown detected. Exercise extreme caution.',
        style: TextStyle(
          color: Color(0xFFF59E0B),
          fontSize: 10,
          fontFamily: 'Courier',
          fontWeight: FontWeight.bold,
        ),
      );
    }
    return null;
  }
}

class _EquityCurvePainter extends CustomPainter {
  final List<double> equityPoints;
  _EquityCurvePainter(this.equityPoints);

  @override
  void paint(Canvas canvas, Size size) {
    if (equityPoints.isEmpty) return;

    final paint = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.0;

    final fillPaint = Paint()
      ..color = Colors.white.withValues(alpha: 0.08)
      ..style = PaintingStyle.fill;

    // Find min and max
    double minVal = equityPoints[0];
    double maxVal = equityPoints[0];
    for (var val in equityPoints) {
      if (val < minVal) minVal = val;
      if (val > maxVal) maxVal = val;
    }

    double range = maxVal - minVal;
    if (range == 0) range = 1.0;

    final double width = size.width;
    final double height = size.height;

    final path = Path();
    final fillPath = Path();

    // Map a value to coordinates
    double getX(int index) {
      if (equityPoints.length <= 1) return 0;
      return index * (width / (equityPoints.length - 1));
    }

    double getY(double value) {
      // Invert Y coordinate for canvas (0 is top)
      return height - 12 - ((value - minVal) / range) * (height - 24);
    }

    // Draw zero threshold baseline if 0.0 is within the range
    if (minVal < 0.0 && maxVal > 0.0) {
      final zeroPaint = Paint()
        ..color = const Color(0xFF27272A)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.0;
      final double zeroY = getY(0.0);
      canvas.drawLine(Offset(0, zeroY), Offset(width, zeroY), zeroPaint);
    }

    // Start paths
    final startX = getX(0);
    final startY = getY(equityPoints[0]);
    path.moveTo(startX, startY);
    fillPath.moveTo(startX, height);
    fillPath.lineTo(startX, startY);

    for (int i = 1; i < equityPoints.length; i++) {
      final x = getX(i);
      final y = getY(equityPoints[i]);
      path.lineTo(x, y);
      fillPath.lineTo(x, y);
    }

    fillPath.lineTo(getX(equityPoints.length - 1), height);
    fillPath.close();

    // Draw area under curve
    canvas.drawPath(fillPath, fillPaint);
    // Draw the main curve line
    canvas.drawPath(path, paint);

    // Draw discrete markers on vertex points
    final dotPaint = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.fill;

    final dotBorderPaint = Paint()
      ..color = const Color(0xFF0A0A0C)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;

    for (int i = 0; i < equityPoints.length; i++) {
      final x = getX(i);
      final y = getY(equityPoints[i]);
      canvas.drawCircle(Offset(x, y), 3.0, dotPaint);
      canvas.drawCircle(Offset(x, y), 3.0, dotBorderPaint);
    }
  }

  @override
  bool shouldRepaint(covariant _EquityCurvePainter oldDelegate) {
    return oldDelegate.equityPoints != equityPoints;
  }
}

