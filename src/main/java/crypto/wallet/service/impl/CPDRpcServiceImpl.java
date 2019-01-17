package crypto.wallet.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import crypto.wallet.service.ERC20AbstractService;
import lombok.Getter;

/**
 * CPD service
 * @author sungjoon.kim
 */
@Service("cpdRpcService") class CPDRpcServiceImpl extends ERC20AbstractService {

	@Getter private final String symbol = SYMBOL_CPD;
	@Getter @Value("${crypto.cpd.minamtgather}") private double minamtgather;
	@Getter @Value("${crypto.cpd.contractaddr}") private String contractaddr;
	@Getter @Value("${crypto.cpd.decimals}") private int decimals;
	
}
