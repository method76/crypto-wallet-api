package crypto.wallet.service.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import crypto.wallet.common.constant.WalletConst;
import crypto.wallet.service.CryptoSharedService;

/**
 */
@Component public class CoinFactory implements WalletConst {
  
    // BTC 계열
	@Autowired private CryptoSharedService btcRpcService;
	// ETH 계열
	@Autowired private CryptoSharedService ethRpcService;
	// ERC20 계열
	@Autowired private CryptoSharedService cpdRpcService;
	
	/**
	 * 심볼명에 대한 서비스 반환
	 * @param symbol
	 * @return
	 */
    public CryptoSharedService getService(String symbol) {
        CryptoSharedService ret = null;
        switch (symbol) {
            case SYMBOL_BTC: ret  = btcRpcService; break;
            case SYMBOL_ETH: ret  = ethRpcService; break;
			case SYMBOL_CPD: ret  = cpdRpcService; break;
        }
        return ret;
    }
    
}
