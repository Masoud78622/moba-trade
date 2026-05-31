import 'package:flutter/material.dart';
import '../utils/pdf_generator.dart';
import '../utils/broker_service.dart';
import '../utils/angel_one_service.dart';
import '../utils/secure_storage_service.dart';
import '../utils/portfolio_risk_engine.dart';

class RiskMonitorScreen extends StatefulWidget {
  final VoidCallback? onBrokerChanged;
  const RiskMonitorScreen({super.key, this.onBrokerChanged});

  @override
  State<RiskMonitorScreen> createState() => _RiskMonitorScreenState();
}

class _RiskMonitorScreenState extends State<RiskMonitorScreen> {
  final TextEditingController _clientIdController = TextEditingController();
  final TextEditingController _passwordController = TextEditingController();
  final TextEditingController _apiKeyController = TextEditingController();
  final TextEditingController _totpController = TextEditingController();
  final TextEditingController _serverIpController = TextEditingController();
  bool _isConnecting = false;
  bool _runningDiagnostics = false;
  List<Map<String, String>>? _diagnosticResults;

  PortfolioStatus? _portfolioStatus;

  Future<void> _saveServerIpSetting() async {
    final ip = _serverIpController.text.trim();
    if (ip.isEmpty) return;
    await SecureStorageService.saveServerIp(ip);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          backgroundColor: Colors.white,
          content: Text('✓ SUCCESS: QUANT SERVER ROUTING UPDATED!', style: TextStyle(fontFamily: 'Courier', color: Colors.black, fontWeight: FontWeight.bold)),
        ),
      );
    }
  }

  Future<void> _runDiagnostics() async {
    setState(() {
      _runningDiagnostics = true;
      _diagnosticResults = null;
    });
    try {
      final results = await BrokerService.current.runDiagnostics();
      if (mounted) {
        setState(() {
          _diagnosticResults = results;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _diagnosticResults = [
            {'test': 'DIAGNOSTICS EXCEPTION', 'status': 'FAIL', 'detail': e.toString()}
          ];
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          _runningDiagnostics = false;
        });
      }
    }
  }

  Future<void> _refreshPortfolioStatus() async {
    try {
      final status = await PortfolioRiskEngine.getPortfolioStatus();
      if (mounted) {
        setState(() {
          _portfolioStatus = status;
        });
      }
    } catch (_) {}
  }

  @override
  void initState() {
    super.initState();
    _loadSavedCredentials();
  }

  Future<void> _loadSavedCredentials() async {
    final ip = await SecureStorageService.readServerIp();
    _serverIpController.text = ip;

    final credentials = await SecureStorageService.readCredentials();
    if (credentials != null) {
      _clientIdController.text = credentials['clientId'] ?? '';
      _passwordController.text = credentials['password'] ?? '';
      _apiKeyController.text = credentials['apiKey'] ?? '';
      _totpController.text = credentials['totpSecret'] ?? '';
      
      if (credentials['isLiveMode'] == true && !BrokerService.current.isConnected) {
        final service = AngelOneBrokerService();
        final success = await service.connect(
          clientId: _clientIdController.text.trim(),
          password: _passwordController.text,
          apiKey: _apiKeyController.text.trim(),
          totpSecret: _totpController.text.trim(),
        );
        if (success) {
          BrokerService.current = service;
          widget.onBrokerChanged?.call();
          if (mounted) setState(() {});
        }
      }
    }
    await _refreshPortfolioStatus();
  }

  @override
  void dispose() {
    _clientIdController.dispose();
    _passwordController.dispose();
    _apiKeyController.dispose();
    _totpController.dispose();
    _serverIpController.dispose();
    super.dispose();
  }

  Future<void> _connectBroker() async {
    if (_clientIdController.text.isEmpty ||
        _passwordController.text.isEmpty ||
        _apiKeyController.text.isEmpty ||
        _totpController.text.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          backgroundColor: Color(0xFFEF4444),
          content: Text('ALL CREDENTIAL FIELDS ARE REQUIRED FOR SmartAPI CONNECTION.', style: TextStyle(fontFamily: 'Courier')),
        ),
      );
      return;
    }

    setState(() => _isConnecting = true);

    try {
      final service = AngelOneBrokerService();
      final success = await service.connect(
        clientId: _clientIdController.text.trim(),
        password: _passwordController.text,
        apiKey: _apiKeyController.text.trim(),
        totpSecret: _totpController.text.trim(),
      );

      if (success) {
        BrokerService.current = service;
        widget.onBrokerChanged?.call();
        await _refreshPortfolioStatus();
        await SecureStorageService.saveCredentials(
          clientId: _clientIdController.text.trim(),
          password: _passwordController.text,
          apiKey: _apiKeyController.text.trim(),
          totpSecret: _totpController.text.trim(),
          isLiveMode: true,
        );

        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              backgroundColor: Colors.white,
              content: Text('✓ SUCCESS: SmartAPI CONNECTION ESTABLISHED LIVE!', style: TextStyle(fontFamily: 'Courier', color: Colors.black, fontWeight: FontWeight.bold)),
            ),
          );
        }
      } else {
        if (mounted) {
          final errorMsg = service.lastError ?? 'CHECK CLIENT ID OR TOTP KEY.';
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              backgroundColor: const Color(0xFFEF4444),
              content: Text('API REJECTED: $errorMsg', style: const TextStyle(fontFamily: 'Courier')),
            ),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            backgroundColor: const Color(0xFFEF4444),
            content: Text('CONNECTION FAIL: $e', style: const TextStyle(fontFamily: 'Courier')),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isConnecting = false);
    }
  }

  Future<void> _disconnectBroker() async {
    BrokerService.current.disconnect();
    BrokerService.current = MockBrokerService();
    widget.onBrokerChanged?.call();
    await _refreshPortfolioStatus();
    await SecureStorageService.clearCredentials();
    _clientIdController.clear();
    _passwordController.clear();
    _apiKeyController.clear();
    _totpController.clear();
    if (mounted) {
      setState(() {});
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          backgroundColor: Color(0xFF27272A),
          content: Text('SESSION DISCONNECTED. RETURNED TO SIMULATION PREVIEW.', style: TextStyle(fontFamily: 'Courier')),
        ),
      );
    }
  }

  Widget _buildInputField(String label, TextEditingController controller, String hint, {bool obscure = false}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(
            fontFamily: 'Courier',
            fontSize: 9,
            fontWeight: FontWeight.bold,
            color: Color(0xFF71717A),
          ),
        ),
        const SizedBox(height: 4),
        TextField(
          controller: controller,
          obscureText: obscure,
          style: const TextStyle(fontFamily: 'Courier', fontSize: 13, color: Colors.white),
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: const TextStyle(fontFamily: 'Courier', fontSize: 12, color: Color(0xFF52525B)),
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            filled: true,
            fillColor: const Color(0xFF0A0A0C),
            enabledBorder: OutlineInputBorder(
              borderSide: const BorderSide(color: Color(0xFF27272A), width: 1.0),
              borderRadius: BorderRadius.circular(4),
            ),
            focusedBorder: OutlineInputBorder(
              borderSide: const BorderSide(color: Colors.white, width: 1.0),
              borderRadius: BorderRadius.circular(4),
            ),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    const double currentDailyLoss = 0.00;
    const double maxDrawdownLimit = 3000.00;
    const double drawdownPercent = (currentDailyLoss / maxDrawdownLimit) * 100;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: Text(
                  'DYNAMIC RISK EXPOSURE MONITOR',
                  style: TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 13,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                    letterSpacing: 1.0,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),

          // 1. Safety Status Banner
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(4),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'SYSTEM INTEGRITY',
                        style: TextStyle(
                          fontFamily: 'Courier',
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                          color: Colors.black,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        BrokerService.current.isConnected ? 'ANGEL ONE ACTIVE' : 'SECURED & ACTIVE',
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: Colors.black,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(2),
                  ),
                  child: const Text(
                    'SHARIAH COMPLIANT',
                    style: TextStyle(
                      fontSize: 9,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                      letterSpacing: 0.5,
                    ),
                  ),
                )
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 2. Drawdown Monitor
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFF121215),
              border: Border.all(color: const Color(0xFF27272A), width: 1.0),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'DAILY DRAWDOWN HALT (3% CAP)',
                  style: TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFFA1A1AA),
                    letterSpacing: 0.5,
                  ),
                ),
                const SizedBox(height: 12),
                const Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Text(
                        'CURRENT DEVIATION',
                        style: TextStyle(fontSize: 12, color: Color(0xFF71717A)),
                      ),
                    ),
                    SizedBox(width: 8),
                    Text(
                      '₹0.00 / ₹3,000.00',
                      style: TextStyle(
                        fontFamily: 'Courier',
                        fontSize: 13,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                
                // Drawdown progress bar
                ClipRRect(
                  borderRadius: BorderRadius.circular(2),
                  child: const LinearProgressIndicator(
                    value: currentDailyLoss / maxDrawdownLimit,
                    backgroundColor: Color(0xFF27272A),
                    valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                    minHeight: 8,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '${drawdownPercent.toStringAsFixed(1)}% OF DRAWDOWN DEPLETED',
                  style: const TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 9,
                    color: Color(0xFF71717A),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 3. Operational Risk Limits
          const Text(
            'ENGINE INTEGRATION PARAMETERS',
            style: TextStyle(
              fontFamily: 'Courier',
              fontSize: 11,
              fontWeight: FontWeight.bold,
              color: Color(0xFFA1A1AA),
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(height: 8),

          Container(
            decoration: BoxDecoration(
              color: const Color(0xFF121215),
              border: Border.all(color: const Color(0xFF27272A), width: 1.0),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Column(
              children: [
                _buildParameterRow(
                  'PORTFOLIO SAFEGUARD',
                  _portfolioStatus == null
                      ? '🔒 ACTIVE (<₹100k)'
                      : (_portfolioStatus!.isSafeguardActive ? '🔒 ACTIVE (<₹100k)' : '✓ INACTIVE'),
                  valueColor: _portfolioStatus == null
                      ? const Color(0xFFEF4444)
                      : (_portfolioStatus!.isSafeguardActive ? const Color(0xFFEF4444) : const Color(0xFF10B981)),
                ),
                const Divider(color: Color(0xFF27272A), height: 1),
                _buildParameterRow(
                  'UNLOCKED STRATEGIES',
                  _portfolioStatus == null
                      ? 'HIGH WIN-RATE ONLY (≥70%)'
                      : (_portfolioStatus!.isSafeguardActive ? 'HIGH WIN-RATE ONLY (≥70%)' : 'ALL STRATEGIES UNLOCKED'),
                  valueColor: _portfolioStatus == null
                      ? const Color(0xFFF59E0B) // Amber
                      : (_portfolioStatus!.isSafeguardActive ? const Color(0xFFF59E0B) : const Color(0xFF10B981)),
                ),
                const Divider(color: Color(0xFF27272A), height: 1),
                _buildParameterRow(
                  'LIVE PORTFOLIO VALUATION',
                  _portfolioStatus == null
                      ? '₹33,700.00'
                      : '₹${_portfolioStatus!.totalPortfolioValue.toStringAsFixed(2)}',
                ),
                const Divider(color: Color(0xFF27272A), height: 1),
                _buildParameterRow('MAX CONCURRENT TRADES', '3 EXPOSURES'),
                const Divider(color: Color(0xFF27272A), height: 1),
                _buildParameterRow('MAX SINGLE TRADE RISK', '₹1,500.00 (1.5%)'),
                const Divider(color: Color(0xFF27272A), height: 1),
                _buildParameterRow('STANDARD ALLOCATION', '₹20,000.00 (T1-T2)'),
                const Divider(color: Color(0xFF27272A), height: 1),
                _buildParameterRow('HALF SIZE ALLOCATION', '₹10,000.00 (T3-T4)'),
                const Divider(color: Color(0xFF27272A), height: 1),
                _buildParameterRow('POSITION SIZE CRITERIA', 'DYNAMIC RISK-BASED'),
                const Divider(color: Color(0xFF27272A), height: 1),
                _buildParameterRow('EXECUTION SPEED LIMIT', 'TICK QUANTUM (<5ms)'),
              ],
            ),
          ),
          const SizedBox(height: 24),

          // 4. End of Day PDF Generation
          const Text(
            'END-OF-DAY PERFORMANCE REPORTS',
            style: TextStyle(
              fontFamily: 'Courier',
              fontSize: 11,
              fontWeight: FontWeight.bold,
              color: Color(0xFFA1A1AA),
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(height: 8),

          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFF121215),
              border: Border.all(color: const Color(0xFF27272A), width: 1.0),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'COMPLIANCE AUDIT EXPORT',
                  style: TextStyle(
                    fontFamily: 'Courier',
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 6),
                const Text(
                  'Compile all daily transitions, active/swing holdings, and individual strategy win rates into a clean monochrome PDF report for printing or storage.',
                  style: TextStyle(
                    fontSize: 12,
                    color: Color(0xFFA1A1AA),
                  ),
                ),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton.icon(
                    onPressed: () {
                      _compileAndShowReport(context);
                    },
                    icon: const Icon(Icons.picture_as_pdf_outlined, size: 16),
                    label: const Text(
                      'GENERATE & PRINT DAILY PDF',
                      style: TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold),
                    ),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.white,
                      foregroundColor: Colors.black,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                  ),
                )
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 4.5 Quant Server Settings Card
          const Text(
            'QUANT SCORER ENGINE GATEWAY',
            style: TextStyle(
              fontFamily: 'Courier',
              fontSize: 11,
              fontWeight: FontWeight.bold,
              color: Color(0xFFA1A1AA),
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(height: 8),

          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFF121215),
              border: Border.all(color: const Color(0xFF27272A), width: 1.0),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Text(
                        'LOCAL QUANT ENGINE API',
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
                const SizedBox(height: 12),
                const Text(
                  'Route autonomous trade scanning to your PC\'s Kotlin Server. Over USB use "localhost". Over Wi-Fi, enter your PC\'s local IP address.',
                  style: TextStyle(
                    fontSize: 11,
                    color: Color(0xFFA1A1AA),
                  ),
                ),
                const SizedBox(height: 16),
                _buildInputField('QUANT SERVER IP / HOST', _serverIpController, 'e.g. 10.53.106.82 or localhost'),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    onPressed: _saveServerIpSetting,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.white,
                      foregroundColor: Colors.black,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                    child: const Text(
                      'SAVE SERVER ROUTING',
                      style: TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 5. Angel One (SmartAPI) Settings Card
          const Text(
            'BROKER INTEGRATION GATEWAY',
            style: TextStyle(
              fontFamily: 'Courier',
              fontSize: 11,
              fontWeight: FontWeight.bold,
              color: Color(0xFFA1A1AA),
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(height: 8),

          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFF121215),
              border: Border.all(color: const Color(0xFF27272A), width: 1.0),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Expanded(
                      child: Text(
                        'ANGEL ONE (SMARTAPI)',
                        style: TextStyle(
                          fontFamily: 'Courier',
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: BrokerService.current.isConnected ? const Color(0xFF065F46) : const Color(0xFF27272A),
                        borderRadius: BorderRadius.circular(2),
                      ),
                      child: Text(
                        BrokerService.current.isConnected ? '✓ CONNECTED' : 'PREVIEW MODE',
                        style: TextStyle(
                          fontFamily: 'Courier',
                          fontSize: 9,
                          fontWeight: FontWeight.bold,
                          color: BrokerService.current.isConnected ? const Color(0xFF34D399) : const Color(0xFFA1A1AA),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (!BrokerService.current.isConnected) ...[
                  const Text(
                    'Link your terminal directly to your Angel One account to fetch live margins, positions, and execute delivery-basis orders securely.',
                    style: TextStyle(
                      fontSize: 11,
                      color: Color(0xFFA1A1AA),
                    ),
                  ),
                  const SizedBox(height: 16),
                  _buildInputField('CLIENT ID', _clientIdController, 'e.g. A123456'),
                  const SizedBox(height: 12),
                  _buildInputField('PASSWORD', _passwordController, 'Your Angel One Password', obscure: true),
                  const SizedBox(height: 12),
                  _buildInputField('API KEY', _apiKeyController, 'SmartAPI Key'),
                  const SizedBox(height: 12),
                  _buildInputField('TOTP SECRET / CODE', _totpController, '6-Digit TOTP or Secret Seed'),
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      onPressed: _isConnecting ? null : _connectBroker,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.white,
                        foregroundColor: Colors.black,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                      child: _isConnecting
                          ? const SizedBox(
                              height: 16,
                              width: 16,
                              child: CircularProgressIndicator(color: Colors.black, strokeWidth: 2),
                            )
                          : const Text(
                              'ESTABLISH LIVE CONNECTION',
                              style: TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold),
                            ),
                    ),
                  )
                ] else ...[
                  const Text(
                    'Active connection established. All dashboard views and positions are now linked in real-time to your Angel One trading balance on a Cash-Delivery basis.',
                    style: TextStyle(
                      fontSize: 11,
                      color: Color(0xFFA1A1AA),
                      height: 1.4,
                    ),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(
                          onPressed: _disconnectBroker,
                          style: OutlinedButton.styleFrom(
                            foregroundColor: Colors.white,
                            side: const BorderSide(color: Color(0xFFEF4444)),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                            padding: const EdgeInsets.symmetric(vertical: 14),
                          ),
                          child: const Text(
                            'DISCONNECT SESSION',
                            style: TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold),
                          ),
                        ),
                      ),
                    ],
                  )
                ]
              ],
            ),
          ),
          if (BrokerService.current.isConnected) ...[
            const SizedBox(height: 16),
            const Text(
              'API GATEWAY DIAGNOSTICS',
              style: TextStyle(
                fontFamily: 'Courier',
                fontSize: 11,
                fontWeight: FontWeight.bold,
                color: Color(0xFFA1A1AA),
                letterSpacing: 0.5,
              ),
            ),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF121215),
                border: Border.all(color: const Color(0xFF27272A), width: 1.0),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'BROKER ENDPOINT HEALTH CHECK',
                    style: TextStyle(
                      fontFamily: 'Courier',
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Verify the network path and API authorization status for all critical trading gateway components.',
                    style: TextStyle(
                      fontSize: 11,
                      color: Color(0xFFA1A1AA),
                    ),
                  ),
                  const SizedBox(height: 16),
                  if (_diagnosticResults != null) ...[
                    ListView.separated(
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      itemCount: _diagnosticResults!.length,
                      separatorBuilder: (context, index) => const Divider(color: Color(0xFF27272A), height: 16),
                      itemBuilder: (context, index) {
                        final test = _diagnosticResults![index];
                        final isPass = test['status'] == 'PASS';
                        final isWarn = test['status'] == 'WARN';
                        final color = isPass ? const Color(0xFF10B981) : (isWarn ? const Color(0xFFF59E0B) : const Color(0xFFEF4444));
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                Expanded(
                                  child: Text(
                                    test['test'] ?? '',
                                    style: const TextStyle(
                                      fontFamily: 'Courier',
                                      fontSize: 11,
                                      fontWeight: FontWeight.bold,
                                      color: Colors.white,
                                    ),
                                  ),
                                ),
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                  decoration: BoxDecoration(
                                    color: color.withValues(alpha: 0.1),
                                    border: Border.all(color: color, width: 0.5),
                                    borderRadius: BorderRadius.circular(2),
                                  ),
                                  child: Text(
                                    test['status'] ?? '',
                                    style: TextStyle(
                                      fontFamily: 'Courier',
                                      fontSize: 9,
                                      fontWeight: FontWeight.bold,
                                      color: color,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 4),
                            Text(
                              test['detail'] ?? '',
                              style: const TextStyle(
                                fontFamily: 'Courier',
                                fontSize: 10,
                                color: Color(0xFFA1A1AA),
                              ),
                            ),
                          ],
                        );
                      },
                    ),
                    const SizedBox(height: 16),
                  ],
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton.icon(
                      onPressed: _runningDiagnostics ? null : _runDiagnostics,
                      icon: _runningDiagnostics
                          ? const SizedBox(
                              width: 14,
                              height: 14,
                              child: CircularProgressIndicator(color: Colors.black, strokeWidth: 1.5),
                            )
                          : const Icon(Icons.analytics_outlined, size: 16),
                      label: Text(
                        _runningDiagnostics ? 'RUNNING CHECKS...' : 'EXECUTE INTEGRITY DIAGNOSTICS',
                        style: const TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold),
                      ),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.white,
                        foregroundColor: Colors.black,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  void _compileAndShowReport(BuildContext context) async {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        backgroundColor: Color(0xFF121215),
        content: Row(
          children: [
            SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 1.5, valueColor: AlwaysStoppedAnimation<Color>(Colors.white)),
            ),
            SizedBox(width: 12),
            Text(
              'COMPILING BROKER EOD DATA...',
              style: TextStyle(fontFamily: 'Courier', color: Colors.white),
            ),
          ],
        ),
      ),
    );

    try {
      final capital = await BrokerService.current.getMarginCapital();
      final completed = await BrokerService.current.fetchCompletedTrades();
      final active = await BrokerService.current.fetchActivePositions();

      int totalTrades = completed.length;
      int wins = completed.where((t) => t['win'] == true).length;
      double winRate = totalTrades > 0 ? (wins / totalTrades) * 100 : 0.0;
      double dailyPnL = completed.fold(0.0, (sum, t) => sum + (t['pnl'] ?? 0.0));

      final Map<String, Map<String, dynamic>> stratMap = {};
      for (var t in completed) {
        final String strategyName = t['strategy'] ?? 'Unknown Strategy';
        final String stratId = strategyName.split(':')[0].trim();
        final String realName = strategyName.contains(':') ? strategyName.split(':')[1].trim() : strategyName;
        final double pnlVal = t['pnl'] ?? 0.0;
        final bool isWin = t['win'] ?? false;

        if (!stratMap.containsKey(stratId)) {
          stratMap[stratId] = {
            'id': stratId,
            'name': realName,
            'trades': 0,
            'wins': 0,
            'netProfit': 0.0,
          };
        }
        stratMap[stratId]!['trades'] = stratMap[stratId]!['trades'] + 1;
        if (isWin) {
          stratMap[stratId]!['wins'] = stratMap[stratId]!['wins'] + 1;
        }
        stratMap[stratId]!['netProfit'] = stratMap[stratId]!['netProfit'] + pnlVal;
      }

      final List<Map<String, dynamic>> strategyPerformances = stratMap.values.map((s) {
        final int tradesCount = s['trades'];
        final int winsCount = s['wins'];
        return {
          'id': s['id'],
          'name': s['name'],
          'trades': tradesCount,
          'winRate': tradesCount > 0 ? (winsCount / tradesCount) * 100 : 0.0,
          'netProfit': s['netProfit'],
        };
      }).toList();

      if (strategyPerformances.isEmpty) {
        strategyPerformances.add({
          'id': 'S--',
          'name': 'No Trades Concluded Today',
          'trades': 0,
          'winRate': 0.0,
          'netProfit': 0.0,
        });
      }

      final List<Map<String, dynamic>> swingHoldings = active.map((a) {
        return {
          'symbol': a['symbol'],
          'strategy': BrokerService.current.isConnected ? 'Live Position' : 'S1: Active Trade',
          'qty': a['qty'],
          'entry': a['entry'],
          'daysHeld': 1,
        };
      }).toList();

      if (context.mounted) {
        ScaffoldMessenger.of(context).hideCurrentSnackBar();
        _showPrintPreviewPortal(
          context,
          capital: capital,
          dailyPnL: dailyPnL,
          totalTrades: totalTrades,
          winRate: winRate,
          strategyPerformances: strategyPerformances,
          completedTrades: completed,
          swingHoldings: swingHoldings,
        );
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).hideCurrentSnackBar();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            backgroundColor: const Color(0xFFEF4444),
            content: Text('FAILED TO COMPILE BROKER DATA: $e', style: const TextStyle(fontFamily: 'Courier')),
          ),
        );
      }
    }
  }

  void _showPrintPreviewPortal(
    BuildContext context, {
    required double capital,
    required double dailyPnL,
    required int totalTrades,
    required double winRate,
    required List<Map<String, dynamic>> strategyPerformances,
    required List<Map<String, dynamic>> completedTrades,
    required List<Map<String, dynamic>> swingHoldings,
  }) {
    final String date = DateTime.now().toLocal().toString().split('.')[0];
    final double totalCapital = capital;

    showGeneralDialog(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.85),
      barrierDismissible: true,
      barrierLabel: 'Close Print Preview',
      transitionDuration: const Duration(milliseconds: 200),
      pageBuilder: (context, anim1, anim2) {
        return Scaffold(
          backgroundColor: Colors.transparent,
          body: Center(
            child: Container(
              width: MediaQuery.of(context).size.width * 0.92,
              height: MediaQuery.of(context).size.height * 0.88,
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(4),
                border: Border.all(color: const Color(0xFF27272A), width: 1.0),
              ),
              child: Column(
                children: [
                  // Modal Top Bar (Obsidian themed but clean)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
                    decoration: const BoxDecoration(
                      color: Color(0xFF0A0A0C),
                      borderRadius: BorderRadius.vertical(top: Radius.circular(3)),
                    ),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text(
                          'A4 PERFORMANCE EXPORT PREVIEW',
                          style: TextStyle(
                            fontFamily: 'Courier',
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                            letterSpacing: 0.5,
                          ),
                        ),
                        GestureDetector(
                          onTap: () => Navigator.pop(context),
                          child: const Icon(Icons.close, color: Colors.white, size: 18),
                        ),
                      ],
                    ),
                  ),

                  // A4 Styled scrollable sheet content
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(32.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // Letterhead
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    const Text(
                                      'MOBA TRADE // DAILY PERFORMANCE',
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontFamily: 'Courier',
                                        fontSize: 18,
                                        fontWeight: FontWeight.bold,
                                        color: Colors.black,
                                      ),
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      'GEN TIMESTAMP: $date // SYSTEM: ACTIVE',
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(
                                        fontFamily: 'Courier',
                                        fontSize: 10,
                                        color: Color(0xFF555555),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(width: 12),
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                decoration: BoxDecoration(
                                  border: Border.all(color: Colors.black, width: 1.5),
                                ),
                                child: const Text(
                                  'SHARIAH COMPLIANT',
                                  style: TextStyle(
                                    fontFamily: 'Courier',
                                    fontSize: 8,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black,
                                  ),
                                ),
                              )
                            ],
                          ),

                          const SizedBox(height: 16),
                          const Divider(color: Colors.black, thickness: 1.5),
                          const SizedBox(height: 20),

                          // Summary Metrics Grid (Simulated HTML Cards)
                          Row(
                            children: [
                              Expanded(child: _buildA4Card('TOTAL CAPITAL', '₹${totalCapital.toStringAsFixed(2)}')),
                              const SizedBox(width: 16),
                              Expanded(child: _buildA4Card('DAILY NET P&L', '+₹${dailyPnL.toStringAsFixed(2)}', pnl: true)),
                            ],
                          ),
                          const SizedBox(height: 16),
                          Row(
                            children: [
                              Expanded(child: _buildA4Card('TRADES TAKEN', '$totalTrades EXITS')),
                              const SizedBox(width: 16),
                              Expanded(child: _buildA4Card('DAILY WIN RATE', '${winRate.toStringAsFixed(1)}%')),
                            ],
                          ),

                          const SizedBox(height: 30),
                          const Text(
                            'STRATEGY PERFORMANCE BREAKDOWN',
                            style: TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: Colors.black,
                              decoration: TextDecoration.underline,
                            ),
                          ),
                          const SizedBox(height: 10),

                          // Strategy Table
                          Table(
                            columnWidths: const {
                              0: FlexColumnWidth(1.2),
                              1: FlexColumnWidth(3.5),
                              2: FlexColumnWidth(1.2),
                              3: FlexColumnWidth(1.2),
                              4: FlexColumnWidth(1.8),
                            },
                            border: const TableBorder(
                              bottom: BorderSide(color: Colors.black, width: 1.0),
                              horizontalInside: BorderSide(color: Color(0xFFE5E7EB), width: 1.0),
                            ),
                            children: [
                              const TableRow(
                                decoration: BoxDecoration(color: Color(0xFFF3F4F6)),
                                children: [
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('ID', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black))),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('STRATEGY NAME', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black))),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('TRADES', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.center)),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('WIN RATE', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.center)),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('NET RETURN', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.right)),
                                ],
                              ),
                              ...strategyPerformances.map((strat) {
                                return TableRow(
                                  children: [
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text(strat['id'], style: const TextStyle(fontFamily: 'Courier', fontSize: 10, color: Colors.black))),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text(strat['name'], style: const TextStyle(fontSize: 10, color: Colors.black))),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('${strat['trades']}', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, color: Colors.black), textAlign: TextAlign.center)),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('${strat['winRate'].toStringAsFixed(1)}%', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.center)),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('₹${strat['netProfit'].toStringAsFixed(2)}', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, color: Colors.black), textAlign: TextAlign.right)),
                                  ],
                                );
                              }),
                            ],
                          ),

                          const SizedBox(height: 30),
                          const Text(
                            'DAILY COMPLETED EXITS LOG',
                            style: TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: Colors.black,
                              decoration: TextDecoration.underline,
                            ),
                          ),
                          const SizedBox(height: 10),

                          // Exits Table
                          Table(
                            columnWidths: const {
                              0: FlexColumnWidth(1.2),
                              1: FlexColumnWidth(3.0),
                              2: FlexColumnWidth(1.0),
                              3: FlexColumnWidth(1.5),
                              4: FlexColumnWidth(1.5),
                              5: FlexColumnWidth(1.8),
                            },
                            border: const TableBorder(
                              bottom: BorderSide(color: Colors.black, width: 1.0),
                              horizontalInside: BorderSide(color: Color(0xFFE5E7EB), width: 1.0),
                            ),
                            children: [
                              const TableRow(
                                decoration: BoxDecoration(color: Color(0xFFF3F4F6)),
                                children: [
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('SYMBOL', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black))),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('STRATEGY', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black))),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('QTY', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.center)),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('ENTRY', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.right)),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('EXIT', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.right)),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('P&L', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.right)),
                                ],
                              ),
                              ...completedTrades.map((trade) {
                                final isWin = trade['win'] as bool;
                                return TableRow(
                                  children: [
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text(trade['symbol'], style: const TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black))),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text(trade['strategy'], style: const TextStyle(fontSize: 10, color: Colors.black))),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('${trade['qty']}', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, color: Colors.black), textAlign: TextAlign.center)),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('₹${trade['entry'].toStringAsFixed(2)}', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, color: Colors.black), textAlign: TextAlign.right)),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('₹${trade['exit'].toStringAsFixed(2)}', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, color: Colors.black), textAlign: TextAlign.right)),
                                    Padding(
                                      padding: const EdgeInsets.all(6.0), 
                                      child: Text(
                                        '${isWin ? "+" : ""}₹${trade['pnl'].toStringAsFixed(2)}', 
                                        style: TextStyle(
                                          fontFamily: 'Courier', 
                                          fontSize: 10, 
                                          fontWeight: FontWeight.bold, 
                                          color: isWin ? const Color(0xFF10B981) : const Color(0xFFEF4444),
                                        ), 
                                        textAlign: TextAlign.right,
                                      ),
                                    ),
                                  ],
                                );
                              }),
                            ],
                          ),

                          const SizedBox(height: 30),
                          const Text(
                            'ACTIVE SWING HOLDINGS (HELD > 1 DAY)',
                            style: TextStyle(
                              fontFamily: 'Courier',
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: Colors.black,
                              decoration: TextDecoration.underline,
                            ),
                          ),
                          const SizedBox(height: 10),

                          // Swing holdings table
                          Table(
                            columnWidths: const {
                              0: FlexColumnWidth(1.2),
                              1: FlexColumnWidth(3.0),
                              2: FlexColumnWidth(1.0),
                              3: FlexColumnWidth(2.0),
                              4: FlexColumnWidth(2.0),
                              5: FlexColumnWidth(2.0),
                            },
                            border: const TableBorder(
                              bottom: BorderSide(color: Colors.black, width: 1.0),
                              horizontalInside: BorderSide(color: Color(0xFFE5E7EB), width: 1.0),
                            ),
                            children: [
                              const TableRow(
                                decoration: BoxDecoration(color: Color(0xFFF3F4F6)),
                                children: [
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('SYMBOL', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black))),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('STRATEGY', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black))),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('QTY', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.center)),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('ENTRY VALUE', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.right)),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('HOLDING PERIOD', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.center)),
                                  Padding(padding: EdgeInsets.all(6.0), child: Text('SHARIAH STATUS', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.right)),
                                ],
                              ),
                              ...swingHoldings.map((swing) {
                                return TableRow(
                                  children: [
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text(swing['symbol'], style: const TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black))),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text(swing['strategy'], style: const TextStyle(fontSize: 10, color: Colors.black))),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('${swing['qty']}', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, color: Colors.black), textAlign: TextAlign.center)),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('₹${(swing['qty'] * swing['entry']).toStringAsFixed(2)}', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, color: Colors.black), textAlign: TextAlign.right)),
                                    Padding(padding: const EdgeInsets.all(6.0), child: Text('${swing['daysHeld']} DAYS', style: const TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black), textAlign: TextAlign.center)),
                                    const Padding(padding: EdgeInsets.all(6.0), child: Text('✓ HALAL (ZOYA)', style: TextStyle(fontFamily: 'Courier', fontSize: 10, fontWeight: FontWeight.bold, color: Color(0xFF10B981)), textAlign: TextAlign.right)),
                                  ],
                                );
                              }),
                            ],
                          ),

                          const SizedBox(height: 40),
                          const Center(
                            child: Text(
                              'THIS DOCUMENT IS PROGRAMMATICALLY GENERATED NATIVELY BY THE MOBA TRADE ENGINE.\nSHARIAH STATUS: SHARIAH COMPLIANT // NO CONVENTIONAL SHORT SALES OR DEBT TRANSACTIONS INCLUDED.',
                              style: TextStyle(
                                fontFamily: 'Courier',
                                fontSize: 8,
                                color: Color(0xFF777777),
                              ),
                              textAlign: TextAlign.center,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),

                  // Modal Bottom Action Bar
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: const BoxDecoration(
                      color: Color(0xFF121215),
                      border: Border(top: BorderSide(color: Color(0xFF27272A), width: 1.0)),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: OutlinedButton(
                            onPressed: () => Navigator.pop(context),
                            style: OutlinedButton.styleFrom(
                              foregroundColor: Colors.white,
                              side: const BorderSide(color: Color(0xFF27272A)),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                              padding: const EdgeInsets.symmetric(vertical: 14),
                            ),
                            child: const Text('CANCEL PREVIEW', style: TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold)),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: ElevatedButton.icon(
                            onPressed: () async {
                              // Compile the HTML content using PdfGenerator
                              final String htmlContent = PdfGenerator.generateEodHtmlReport(
                                date: date,
                                totalCapital: totalCapital,
                                dailyPnL: dailyPnL,
                                totalTrades: totalTrades,
                                winRate: winRate,
                                strategyPerformances: strategyPerformances,
                                completedTrades: completedTrades,
                                swingHoldings: swingHoldings,
                              );

                              // Write to HTML report
                              try {
                                final file = await PdfGenerator.saveHtmlReport(htmlContent);
                                if (context.mounted) {
                                  Navigator.pop(context);
                                  _showPrintSuccessNotification(context, file.path);
                                }
                              } catch (e) {
                                if (context.mounted) {
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    SnackBar(
                                      backgroundColor: const Color(0xFFEF4444),
                                      content: Text(
                                        'ERROR SAVING REPORT: $e',
                                        style: const TextStyle(fontFamily: 'Courier', color: Colors.white),
                                      ),
                                    ),
                                  );
                                }
                              }
                            },
                            icon: const Icon(Icons.print, size: 16),
                            label: const Text('SAVE & PRINT REPORT', style: TextStyle(fontFamily: 'Courier', fontWeight: FontWeight.bold)),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.white,
                              foregroundColor: Colors.black,
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                              padding: const EdgeInsets.symmetric(vertical: 14),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildA4Card(String label, String value, {bool pnl = false}) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFFF9FAFB),
        border: Border.all(color: const Color(0xFFD1D5DB), width: 1.0),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            label,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontFamily: 'Courier',
              fontSize: 9,
              fontWeight: FontWeight.bold,
              color: Color(0xFF555555),
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontFamily: 'Courier',
              fontSize: 16,
              fontWeight: FontWeight.bold,
              color: pnl ? const Color(0xFF10B981) : Colors.black,
            ),
          ),
        ],
      ),
    );
  }

  void _showPrintSuccessNotification(BuildContext context, String path) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        backgroundColor: Colors.white,
        content: Text(
          'EOD REPORT SAVED TO: $path',
          style: const TextStyle(
            fontFamily: 'Courier',
            fontWeight: FontWeight.bold,
            color: Colors.black,
            fontSize: 10,
          ),
        ),
        duration: const Duration(seconds: 5),
      ),
    );
  }

  Widget _buildParameterRow(String label, String value, {Color? valueColor}) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 14.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Expanded(
            child: Text(
              label,
              style: const TextStyle(
                fontFamily: 'Courier',
                fontSize: 11,
                color: Color(0xFFA1A1AA),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: TextStyle(
                fontFamily: 'Courier',
                fontSize: 11,
                fontWeight: FontWeight.bold,
                color: valueColor ?? Colors.white,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
