package crypto.wallet.service.common;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import crypto.wallet.common.constant.WalletConst;
import crypto.wallet.common.domain.req.BonusRequest;
import crypto.wallet.common.domain.req.ManualPayRequest;
import crypto.wallet.common.domain.req.PersonalInfoRequest;
import crypto.wallet.common.domain.req.PointExchangeRequest;
import crypto.wallet.common.domain.req.SendRequest;
import crypto.wallet.common.domain.req.TokenBuyRequest;
import crypto.wallet.common.domain.res.BonusResponse;
import crypto.wallet.common.domain.res.CryptoBalancesResponse;
import crypto.wallet.common.domain.res.CryptoIndexResponse;
import crypto.wallet.common.domain.res.FiatDepositResponse;
import crypto.wallet.common.domain.res.ManualPayResponse;
import crypto.wallet.common.domain.res.PointExchangeResponse;
import crypto.wallet.common.domain.res.PrivateSaleTXResponse;
import crypto.wallet.common.domain.res.PurchaseResponse;
import crypto.wallet.common.domain.res.SendResponse;
import crypto.wallet.common.domain.res.SystemStatusResponse;
import crypto.wallet.common.domain.res.TokenBuyResponse;
import crypto.wallet.common.domain.res.TotalBalancesResponse;
import crypto.wallet.common.domain.res.TransactionResponse;
import crypto.wallet.common.util.WalletUtil;
import crypto.wallet.data.domain.TbAddressBalance;
import crypto.wallet.data.domain.TbBonusRequest;
import crypto.wallet.data.domain.TbCryptoMaster;
import crypto.wallet.data.domain.TbFiatDeposit;
import crypto.wallet.data.domain.TbManualPayRequest;
import crypto.wallet.data.domain.TbMarketPrice;
import crypto.wallet.data.domain.TbPointExchangeRequest;
import crypto.wallet.data.domain.TbRecv;
import crypto.wallet.data.domain.TbSend;
import crypto.wallet.data.domain.TbTokenBuyRequest;
import crypto.wallet.data.domain.VwUserKrwBalance;
import crypto.wallet.data.gson.common.BitcoinStringResponse;
import crypto.wallet.data.gson.common.BonusBalance;
import crypto.wallet.data.gson.common.CryptoBalanceByAddr;
import crypto.wallet.data.gson.common.CryptoIndex;
import crypto.wallet.data.gson.common.CryptoIndex.MarketData.CurrentPrice;
import crypto.wallet.data.gson.common.TotalBalance;
import crypto.wallet.data.key.PkSymbolOrderId;
import crypto.wallet.data.key.PkUidSymbol;
import crypto.wallet.data.key.PkUidSymbolOrderId;
import crypto.wallet.repo.AddressBalanceRepository;
import crypto.wallet.repo.BonusRequestRepository;
import crypto.wallet.repo.CryptoMasterRepository;
import crypto.wallet.repo.FiatDepositRepository;
import crypto.wallet.repo.ManualPayRequestRepository;
import crypto.wallet.repo.MarketPriceRepository;
import crypto.wallet.repo.PointExchangeRequestRepository;
import crypto.wallet.repo.RecvRepository;
import crypto.wallet.repo.SendRepository;
import crypto.wallet.repo.TokenBuyRequestRepository;
import crypto.wallet.repo.UserKrwBalanceRepository;
import crypto.wallet.service.CryptoSharedService;
import crypto.wallet.service.intf.OwnChain;
import edu.emory.mathcs.backport.java.util.Arrays;
import lombok.extern.slf4j.Slf4j;


@Slf4j @Service("tokenSaleService") 
public class TokenSaleService implements WalletConst {

	private final String API_ENDPOINT 	= "https://api.coingecko.com/api/v3/coins/";
	private final String PARAM_ETH 		= "ethereum";
	private final String PARAM_BTC 		= "bitcoin";
	private final String Q_TOTAL_ORDER_COUNT 
			= "select count(order_id) from crypto.wallet.data.domain.TbTokenBuyRequest" 
				+ " where error is null and token_symbol = :symbol";
    private final String Q_CURRENT_SALE = "SELECT sum(token_amount) as token_amount, pay_symbol"
    		+ " FROM crypto.wallet.data.domain.TbTokenBuyRequest"
    		+ " where token_symbol = :token_symbol and status = 'C'"
    		+ " group by pay_symbol";
    private final String FREE_MEM   	= "getFreePhysicalMemorySize";
    private final String TOTAL_MEM  	= "getTotalPhysicalMemorySize";
    private final String USAGE_CPU  	= "getSystemCpuLoad";
    private final String URL_BEST_BLOCKHEIGHT_INFURA = "https://api.infura.io/v1/jsonrpc/mainnet/eth_blockNumber";
    private final String URL_BONUS_BALANCE_CELLPINDA = "https://wallet.cellpinda.com/q/public/wallet_public_proc/?c=balance&uid=";
    private final String URL_POINT_BALANCE_CELLPINDA = "https://wallet.cellpinda.com/q/public/wallet_public_proc/?c=point&uid=";

	@Value("${app.diskCheckDir}") String CHECK_DISK_DIR;
	@Value("${app.enabledSymbols}") String[] ENABLED_SYMBOLS;
	@Value("${app.enabledERC20s}") String[] ENABLED_ERC20S;
	@Value("${crypto.eth.reserveaddr}") private String ETH_RESERVE_ADDR;
	@Value("${crypto.cpd.rewarehouseaddr}") private String CPD_REWARE_ADDR;
	
	@Autowired private CoinFactory coinFactory;
	@Autowired private MarketPriceRepository marketPriceRepo;
	@Autowired private RecvRepository recvRepo;
    @Autowired private SendRepository sendRepo;
    @Autowired private CryptoMasterRepository cryptoMasterRepo;
    @Autowired private AddressBalanceRepository addressBalanceRepo;
    @Autowired private TokenBuyRequestRepository tokenBuyRequestRepo;
    @Autowired private UserKrwBalanceRepository userKrwBalanceRepo;
    @Autowired private FiatDepositRepository fiatDepositRepo;
    @Autowired private BonusRequestRepository bonusRequestRepo;
    @Autowired private ManualPayRequestRepository manualPayRequestRepository;
    @Autowired private PointExchangeRequestRepository pointExchangeRequestRepository;
    @Autowired private EntityManagerFactory emf;
    private Gson gson = new Gson();
    /*
	private final String BALANCE_QUERY  = "select a.symbol, b.addr," 
		 + " case when b.balance is null then 0 else b.balance end as balance,"  
		 + " case when b.recv is null then 0 else b.recv end as recv,"
		 + " case when b.send is null then 0 else b.send end as send,"
		 + " case when b.buy is null then 0 else b.buy end as buy,"
		 + " case when b.actual is null then 0 else b.actual end as actual"
		 + " from tb_crypto_master a"  
		 + " left outer join tb_address_balance b on a.symbol = b.symbol"
		 + " and b.uid = %d";
	 private final String KRW_BAL_STR 
	 	= "select symbol, sum(amount) as balance, sum(amount) as actual, 0 as buy"
		+ " 0 as recv, 0 as send from tb_fiat_deposit"
		+ " where symbol = 'KRW' and uid = :uid group by uid, symbol";
    */
    
    @Transactional(readOnly = true) public CryptoIndexResponse getIndex() {
    	List<TbMarketPrice> data = marketPriceRepo.findAll();
    	CryptoIndexResponse res = new CryptoIndexResponse();
    	res.setResult(data);
    	return res;
    }
    
	/**
	 * https://api.coingecko.com/api/v3/coins/ethereum
	 * 초당 100회 제한
	 * market_data > current_price > usd/krw
	 * market_data > current_price > krw
	 * N ETH => M KRW => L CPD
	 * @return
	 * @throws Exception 
	 */
	@Transactional public CurrentPrice getEthPrice() throws Exception {
		CurrentPrice price = null;
		String resStr = WalletUtil.sendHttpsGet(API_ENDPOINT + PARAM_ETH);
		CryptoIndex res = gson.fromJson(resStr, CryptoIndex.class);
		price = res.getMarket_data().getCurrent_price();
		TbMarketPrice datum = new TbMarketPrice(SYMBOL_ETH, price.getKrw(), price.getUsd());
		marketPriceRepo.save(datum);
		return price;
	}
	
	/**
	 * https://api.coingecko.com/api/v3/coins/bitcoin
	 * 초당 100회 제한
	 * market_data > current_price > krw
	 * N BTC => M BTC => L CPD
	 * @return
	 * @throws IOException 
	 * @throws JsonSyntaxException
	 */
	@Transactional public CurrentPrice getBtcPrice() throws Exception {
		CurrentPrice price = null;
		String resStr = WalletUtil.sendHttpsGet(API_ENDPOINT + PARAM_BTC);
		CryptoIndex res = gson.fromJson(resStr, CryptoIndex.class);
		price = res.getMarket_data().getCurrent_price();
		TbMarketPrice datum = new TbMarketPrice(SYMBOL_BTC, price.getKrw(), price.getUsd());
		marketPriceRepo.save(datum);
		return price;
	}

	/**
	 * 관리자용) 전체 구매내역 조회
	 * @param param
	 * @return
	 */
	@Transactional(readOnly = true)
	public PurchaseResponse getAllPurchases(PersonalInfoRequest param) {
		List<PrivateSaleTXResponse> list = new ArrayList<>();
		// 1) 토큰 구매 건 개요: 이더인 경우만 전체 과정 체크, 나머지는 바로 토큰 입금 처리
		List<TbTokenBuyRequest> data = 
				tokenBuyRequestRepo.findByTokenSymbolOrderByRegDtDesc(param.getSymbol());
		for (TbTokenBuyRequest datum : data) {
			list.add(new PrivateSaleTXResponse(datum));
		}
		PurchaseResponse ret = new PurchaseResponse(list);
		EntityManager em = emf.createEntityManager();
		Long results = (Long) em.createQuery(Q_TOTAL_ORDER_COUNT)
				.setParameter("symbol", param.getSymbol())
						.getSingleResult();
        em.close();
        log.info("results " + results);
        ret.getResult().setTotalCount(results);
		return ret;
	}
	
	/**
	 * 사용자) 토큰 구매이력 조회
	 * @param param
	 * @return
	 */
	@Transactional(readOnly = true)
	public List<PrivateSaleTXResponse> getTokenBuyHistory(PersonalInfoRequest param) {
		List<PrivateSaleTXResponse> ret = new ArrayList<>();
		List<TbTokenBuyRequest> list = tokenBuyRequestRepo.findByUidOrderByRegDtDesc(
				param.getUid());
		for (TbTokenBuyRequest datum : list) {
			ret.add(new PrivateSaleTXResponse(datum));
		}
		return ret;
	}

	/**
	 * 단순 입출금 내역 조회
	 * @param param
	 * @return
	 */
	@Transactional(readOnly = true) public List<TransactionResponse> getTxHistory(
			PersonalInfoRequest param) {
		
		List<TransactionResponse> ret = new ArrayList<>();
		// 1) 토큰 구매건이 아닌 출금 건
		List<TbSend> list1 = sendRepo.findByUidAndIntent(param.getUid(), INTENT_PURETX);
		for (TbSend datum : list1) {
			ret.add(new TransactionResponse(datum));
		}
		// 2) 토큰 구매건이 아닌 입금 건
		List<TbRecv> list2 = recvRepo.findByUidAndIntent(param.getUid(), INTENT_PURETX);
		for (TbRecv datum : list2) {
			ret.add(new TransactionResponse(datum));
		}
		
		Collections.sort(ret, new Comparator<TransactionResponse>() {
			@Override public int compare(TransactionResponse s1, TransactionResponse s2){
		        long n1 = s1.getTxtime();
		        long n2 = s2.getTxtime();
		        if (n1 == n2) return 0;
		        else if (n1 == 0) return -1;
		        else if (n2 == 0) return 1;
		        else if (n1 > n2) return -1;
		        else if (n1 < n2) return 1;
		        else return 0;
		    }
		});
		
		return ret;
	}
	
	@Transactional(readOnly = true) public List<TransactionResponse> getTxHistory(String orderId) {
		List<TransactionResponse> ret = new ArrayList<>();
		// 1) 토큰 구매관련 출금 건
		List<TbSend> data = sendRepo.findByOrderIdOrderByRegDtDesc(orderId);
		for (TbSend datum : data) {
			ret.add(new TransactionResponse(datum));
		}
		return ret;
	}
	
	/**
	 * 
	 * @param uid
	 * @return
	 */
	@Transactional(readOnly = true) 
	public CryptoBalancesResponse getBalancesByUid(int uid) {

		List<CryptoBalanceByAddr> list = new ArrayList<>();
		CryptoBalancesResponse ret = new CryptoBalancesResponse();
		List<TbAddressBalance> data = addressBalanceRepo.findByUid(uid);
		// 원화를 제외한 나머지 암호화폐 잔고조회
		for (TbAddressBalance datum : data) {
			CryptoBalanceByAddr bal = new CryptoBalanceByAddr(datum);
			list.add(bal);
		}
		// 원화 입금 집계 쿼리
		PkUidSymbol id = new PkUidSymbol(uid, SYMBOL_KRW);
		Optional<VwUserKrwBalance> rawdatum = userKrwBalanceRepo.findById(id);
		CryptoBalanceByAddr datum1 = new CryptoBalanceByAddr(SYMBOL_KRW);
		if (rawdatum.isPresent()) {
			VwUserKrwBalance datum = rawdatum.get();
			datum1.setRecv(datum.getRecv());
			datum1.setBuy(datum.getBuy());
			datum1.setBalance(datum.getRecv() - datum.getBuy());
		} else {
			datum1.setBalance(0);
		}
		datum1.setActual(datum1.getBalance());
		datum1.setSend(0);
		list.add(datum1);
    	ret.setResult(list);
        return ret;
	}
	
	/**
	 * 
	 * @return
	 */
	@Transactional public TotalBalancesResponse getTotalBalances() {
    	TotalBalancesResponse ret = new TotalBalancesResponse();
        List<TbCryptoMaster> data = cryptoMasterRepo.findAll();
        List<TotalBalance> balances = new ArrayList<>();
        for (TbCryptoMaster datum : data) {
        	TotalBalance bal = new TotalBalance(datum.getSymbol(),
        			datum.getTotalBal(), datum.getSendMastBal(), datum.getTheOtherBal());
        	balances.add(bal);
        }
        ret.setResult(balances);
        return ret;
    }
	
	/**
	 * 원화 입금
	 * String orderId, int uid, String symbol, double amount
	 * @param res
	 * @return
	 */
	@Transactional public FiatDepositResponse fiatDeposit(
			FiatDepositResponse res) {
		PkUidSymbolOrderId id = new PkUidSymbolOrderId(
				res.getResult().getOrderId()
				, res.getResult().getUid()
				, res.getResult().getSymbol());
		Optional<TbFiatDeposit> fraw = fiatDepositRepo.findById(id);
		if (fraw.isPresent()) {
			res.setCode(CODE_FAIL_DUP_ORDER_ID);
			res.setError(MSG_DUP_ORDER_ID);
		} else {
			TbFiatDeposit datum1 = new TbFiatDeposit(res);
			fiatDepositRepo.save(datum1);	
		}
		return res;
	}

	/**
	 * 토큰 구매처리 API
	 * @param param
	 */
	@Transactional public TokenBuyResponse requestBuyToken(TokenBuyRequest param) {

		TokenBuyResponse ret = new TokenBuyResponse(param);
		// 잔고 먼저 업데이트, KRW인 경우와 ETH인 경우 구분
		TbTokenBuyRequest buydatum = new TbTokenBuyRequest(param);
		double balance = 0;
		CryptoSharedService service = coinFactory.getService(param.getPaySymbol());
		TbAddressBalance addrbaldatum = null;
		
		if (!SYMBOL_KRW.equals(param.getPaySymbol())) {
			// 1) 원화 구매가 아닌 경우: 암호화폐 구매인 경우
	    	// 	=> 먼저 구매 요청자 잔고 체크
	        boolean success = service.syncWalletBalance(param.getUid());
			if (!success) {
				ret.setCode(CODE_FAIL_LOGICAL);
				return ret;
			}
			// 암호화폐 잔고 확인
			PkUidSymbol id = new PkUidSymbol(param.getUid(), 
							param.getPaySymbol());
			Optional<TbAddressBalance> rawaddrdatum = addressBalanceRepo.findById(id);
			
			if (!rawaddrdatum.isPresent()) {
				// 지갑이 없거나 잔고 부족
				ret.getResult().setStatus(STAT_FAILED);
				buydatum.setStatus(STAT_FAILED);
				ret.setCode(CODE_WALLET_NOT_EXISTS);
				ret.setError(MSG_WALLET_NOT_EXISTS);
				buydatum.setError(MSG_WALLET_NOT_EXISTS);
				tokenBuyRequestRepo.save(buydatum);
				return ret;
			} else {
				// 지갑주소 있음
				addrbaldatum = rawaddrdatum.get();
				balance = addrbaldatum.getBalance();
				if (balance < param.getPayAmount()) {
					// 잔고 부족
					ret.getResult().setStatus(STAT_FAILED);
					buydatum.setStatus(STAT_FAILED);
					buydatum.setFromAddr(addrbaldatum.getAddr());
					ret.setCode(CODE_FAIL_NOT_ENOUGH_BALANCE);
					ret.setError(MSG_NOT_ENOUGH_BALANCE);
					buydatum.setError(MSG_NOT_ENOUGH_BALANCE);
					tokenBuyRequestRepo.save(buydatum);
					return ret;
				}
			}
			buydatum.setFromAddr(addrbaldatum.getAddr());
			
		} else {
			// 2) 원화구매 인 경우
			// 	=> Todo: 원화 잔고체크
			CryptoBalancesResponse bals = getBalancesByUid(param.getUid());
			List<CryptoBalanceByAddr> data = bals.getResult();
			for (CryptoBalanceByAddr datum : data) {
				if (SYMBOL_KRW.equals(datum.getSymbol())) {
					balance = datum.getBalance();
					log.info("KRWBalance", "" + balance);
					if (balance < param.getPayAmount()) {
						// 잔고 부족
						ret.getResult().setStatus(STAT_FAILED);
						buydatum.setStatus(STAT_FAILED);
						ret.setCode(CODE_FAIL_NOT_ENOUGH_BALANCE);
						ret.setError(MSG_NOT_ENOUGH_BALANCE);
						buydatum.setError(MSG_NOT_ENOUGH_BALANCE);
						tokenBuyRequestRepo.save(buydatum);
						return ret;
					}
				}
			}
		}
		
		// 3) 토큰구매 공통처리 로직
		// 중복 구매요청 확인
		Optional<TbTokenBuyRequest> prevOrder = tokenBuyRequestRepo.findById(
				param.getOrderId());
		if (prevOrder.isPresent()) {
			ret.setCode(CODE_FAIL_DUP_ORDER_ID);
			ret.setError(MSG_DUP_ORDER_ID);
			return ret;
		}
		
    	// Status Code: A > D > P > W > C | F
    	if (SYMBOL_BTC.equals(param.getPaySymbol())
    			|| SYMBOL_KRW.equals(param.getPaySymbol())) {
    		// BTC/KRW의 경우 DB 잔고만 변경?
    		PkUidSymbol id = new PkUidSymbol(ret.getResult().getUid(), SYMBOL_ETH);
    		Optional<TbAddressBalance> araw = addressBalanceRepo.findById(id);
    		if (araw.isPresent()) {
        		buydatum.setFromAddr(araw.get().getAddr());	
    		} else {
    			buydatum.setError(MSG_EMPTY_ADDRESS);
    			ret.setCode(CODE_WALLET_NOT_EXISTS);
    		}
			buydatum.setStatus(STAT_PROGRESS);
    		ret.getResult().setStatus(STAT_PROGRESS);
    		
    	} else if (SYMBOL_ETH.equals(param.getPaySymbol())) {
        	// ETH의 경우 상태를 ACCEPTED로 바꾸고 셀핀다 중앙지갑으로 출금처리
    		buydatum.setStatus(STAT_ACCEPTED);
    		ret.getResult().setStatus(STAT_ACCEPTED);
    		SendRequest req = new SendRequest(
    	    		param.getUid(), param.getPaySymbol(), 
    				service.getSendaddr(), param.getPayAmount(),
    				param.getOrderId(), addrbaldatum.getBrokerId());
    		req.setIntent(INTENT_BUY_TOKEN);
    		req.setFromAddress(addrbaldatum.getAddr());
    		// 이더 출금요청 저장
    		SendResponse res = service.requestSendTransaction(req);
    		if (res.getCode()!=CODE_SUCCESS) {
    			ret.setCode(res.getCode());
    			ret.setError(res.getError());
    			return ret;
    		}
    		addressBalanceRepo.save(addrbaldatum);
    	}
		tokenBuyRequestRepo.save(buydatum);
		return ret;
	}
	
	/**
	 * 토큰 구매처리 API
	 * @param param
	 */
	@Transactional public BonusResponse requestBonusToken(BonusRequest param) {

		BonusResponse ret = new BonusResponse(param);
	
		// 1) 존재하는 주소인지 체크
		PkUidSymbol id1 = new PkUidSymbol(param.getUid(), param.getSymbol());
		Optional<TbAddressBalance> rawaddr = addressBalanceRepo.findById(id1);
		if (!rawaddr.isPresent()) {
			ret.setCode(CODE_WALLET_NOT_EXISTS);
			ret.setError(MSG_WALLET_NOT_EXISTS);
			return ret;
		}
		
		// 2) 가용 보너스 잔고체크: URL_BONUS_BALANCE_CELLPINDA
		BonusBalance bal = null;
		try {
			String resStr = WalletUtil.sendHttpsGet(URL_BONUS_BALANCE_CELLPINDA + param.getUid());
			bal = gson.fromJson(resStr, BonusBalance.class);
		} catch(Exception e) {
			e.printStackTrace();
			ret.setCode(CODE_FAIL_IO_PROBLEM);
			ret.setError(MSG_IO_PROBLEM);
			return ret;
		}
		
		// 3) 중복 구매요청 확인
		PkSymbolOrderId id2 = new PkSymbolOrderId(param.getSymbol(), param.getOrderId());
		Optional<TbBonusRequest> prevOrder = bonusRequestRepo.findById(id2);
		if (prevOrder.isPresent()) {
			ret.setCode(CODE_FAIL_DUP_ORDER_ID);
			ret.setError(MSG_DUP_ORDER_ID);
			return ret;
		}
		
		// 4) 보너스 요청 데이터 저장
		TbBonusRequest datum = new TbBonusRequest(param);
		datum.setToAddr(rawaddr.get().getAddr());
		datum.setBonusBal(bal.getValue());
		bonusRequestRepo.save(datum);
		
		// 5) 송금요청 데이터 저장
		CryptoSharedService service = coinFactory.getService(param.getSymbol());
		SendRequest req = new SendRequest(param.getUid(), param.getSymbol(), 
				rawaddr.get().getAddr(), param.getAmount(),
				param.getOrderId(), BROKER_ID_SYSTEM);
		req.setIntent(INTENT_BONUS_TOKEN);
		
		// 이더 출금요청 저장
		SendResponse res = service.requestSendTransaction(req);
		if (res.getCode()!=CODE_SUCCESS) {
			ret.setCode(res.getCode());
			ret.setError(res.getError());
			return ret;
		}
		
		return ret;
	}
	
	/**
	 * 
	 * @param param
	 * @return
	 */
	@Transactional public PointExchangeResponse requestPointExchange(PointExchangeRequest param) {

		PointExchangeResponse ret = new PointExchangeResponse(param);
	
		// 1) 주소 존재여부 체크
		PkUidSymbol id1 = new PkUidSymbol(param.getUid(), param.getTokenSymbol());
		Optional<TbAddressBalance> rawaddr = addressBalanceRepo.findById(id1);
		if (!rawaddr.isPresent()) {
			ret.setCode(CODE_WALLET_NOT_EXISTS);
			ret.setError(MSG_WALLET_NOT_EXISTS);
			return ret;
		}
		
		// 2) 가용 잔고체크 (포인트)
		BonusBalance bal = null;
		try {
			String resStr = WalletUtil.sendHttpsGet(URL_POINT_BALANCE_CELLPINDA + param.getUid());
			bal = gson.fromJson(resStr, BonusBalance.class);
			if (bal.getValue()>param.getPointAmount()) {
				ret.setCode(CODE_FAIL_NOT_ENOUGH_BALANCE);
				ret.setError(MSG_NOT_ENOUGH_BALANCE);
				return ret;
			}
		} catch(Exception e) {
			e.printStackTrace();
			ret.setCode(CODE_FAIL_IO_PROBLEM);
			ret.setError(MSG_IO_PROBLEM);
			return ret;
		}
		
		// 3) 중복요청 확인
		Optional<TbPointExchangeRequest> prevOrder = pointExchangeRequestRepository.findById(param.getOrderId());
		if (prevOrder.isPresent()) {
			ret.setCode(CODE_FAIL_DUP_ORDER_ID);
			ret.setError(MSG_DUP_ORDER_ID);
			return ret;
		}
		
		// 4) 요청 데이터 저장
		TbPointExchangeRequest datum = new TbPointExchangeRequest(param);
		datum.setFromAddr(rawaddr.get().getAddr());
		pointExchangeRequestRepository.save(datum);
		
		// 5) 송금요청 데이터 저장
		CryptoSharedService service = coinFactory.getService(param.getTokenSymbol());
		SendRequest req = new SendRequest(param.getUid(), param.getTokenSymbol(), 
				CPD_REWARE_ADDR, param.getTokenAmount(),
				param.getOrderId(), BROKER_ID_SYSTEM);
		req.setFromAddress(rawaddr.get().getAddr());
		req.setIntent(INTENT_POINT_EXCHANGE);
		
		// 이더 출금요청 저장
		SendResponse res = service.requestSendTransaction(req);
		req.setNotifiable('Y');
		if (res.getCode()!=CODE_SUCCESS) {
			ret.setCode(res.getCode());
			ret.setError(res.getError());
			return ret;
		}
		
		return ret;
	}
	
	/**
	 * 토큰 수동 지급처리 API
	 * @param param
	 */
	@Transactional public ManualPayResponse requestManualPay(ManualPayRequest param) {

		ManualPayResponse ret = new ManualPayResponse(param);
	
		// 1) 주소 존재여부 체크
		PkUidSymbol id1 = new PkUidSymbol(param.getUid(), param.getTokenSymbol());
		Optional<TbAddressBalance> rawaddr = addressBalanceRepo.findById(id1);
		if (!rawaddr.isPresent()) {
			ret.setCode(CODE_WALLET_NOT_EXISTS);
			ret.setError(MSG_WALLET_NOT_EXISTS);
			return ret;
		}
		
		// 2) 중복요청 확인
		Optional<TbManualPayRequest> prevOrder = manualPayRequestRepository.findById(param.getOrderId());
		if (prevOrder.isPresent()) {
			ret.setCode(CODE_FAIL_DUP_ORDER_ID);
			ret.setError(MSG_DUP_ORDER_ID);
			return ret;
		}
		
		// 3) 요청 데이터 저장
		TbManualPayRequest datum = new TbManualPayRequest(param);
		datum.setStatus(STAT_ACCEPTED);
		datum.setToAddr(rawaddr.get().getAddr());
		manualPayRequestRepository.save(datum);
		
		// 4) 송금요청 데이터 저장
		CryptoSharedService service = coinFactory.getService(param.getTokenSymbol());
		SendRequest req = new SendRequest(param.getUid(), param.getTokenSymbol(), 
				rawaddr.get().getAddr(), param.getTokenAmount(),
				param.getOrderId(), BROKER_ID_SYSTEM);
		req.setNotifiable('Y');
		req.setIntent(INTENT_MANUAL_PAY);
		
		// 5) 출금요청 저장
		SendResponse res = service.requestSendTransaction(req);
		if (res.getCode()!=CODE_SUCCESS) {
			ret.setCode(res.getCode());
			ret.setError(res.getError());
			return ret;
		}
		
		return ret;
	}
	
    /**
     */
    @Transactional public int[] retrieveTokensToUserPaid() {
    	
    	int[] ret = {0, 0};
    	CryptoSharedService service = coinFactory.getService(SYMBOL_ETH);
    	// 1) 암호화폐/원화 임금 확인된 후 토큰 입금 전 상태 처리: 
    	List<TbTokenBuyRequest> data2 = tokenBuyRequestRepo.findByStatus(
    			STAT_PROGRESS);
    	if (data2==null || data2.size()<1) { return ret; }
    	
    	ret[1] += data2.size();
    	List<String> tokens = Arrays.asList(ENABLED_ERC20S);
    	
    	for (TbTokenBuyRequest datum : data2) {
    		if (!tokens.contains(datum.getTokenSymbol())) {
    			log.error("selectingTokenBuyReq", MSG_INVALID_BUY_TOKEN);
    			datum.setError(MSG_INVALID_BUY_TOKEN);
    			continue;
    		}
    		// 토큰 지급 요청
	    	SendRequest req = new SendRequest(datum.getTokenSymbol(), datum.getOrderId(), 
	    			UID_SYSTEM, datum.getFromAddr(), null, datum.getTokenAmount(), 
	    			BROKER_ID_SYSTEM, 0);
	    	req.setIntent(INTENT_BUY_TOKEN);
	    	req.setNotifiable('Y');
	        SendResponse res = service.requestSendTransaction(req);
	        if (res!=null && res.getCode()==CODE_SUCCESS) {
	        	datum.setStatus(STAT_WITHDRAWING);
	        	ret[0]++;
	        } else {
	        	datum.setError(res.getError());
	        }
	    }
    	tokenBuyRequestRepo.saveAll(data2);
    	return ret;
    }

    public SystemStatusResponse getSystemStatus() {
    	
    	SystemStatusResponse ret = new SystemStatusResponse();
    	
    	OperatingSystemMXBean operatingSystemMXBean = 
    			ManagementFactory.getOperatingSystemMXBean();
    	long freeMem = 0, totalMem = 0;
    	long freeDisk = 0, totalDisk = 0;
    	float cpuUsagePerc = 0;
    	for (Method method : operatingSystemMXBean.getClass().getDeclaredMethods()) {
    		method.setAccessible(true);
    		if (method.getName().startsWith("get")
    				&& Modifier.isPublic(method.getModifiers())) {
				Object value;
				try {
					value = method.invoke(operatingSystemMXBean);
				} catch (Exception e) {
				    value = e;
				} // try
				// getProcessCpuLoad(JVM CPU 사용률): 0 ~ 1.0
				// getSystemCpuLoad(시스템 CPU 사용률): 0 ~ 1.0
				System.out.println(method.getName() + " = " + value);
				if (FREE_MEM.equals(method.getName())){
					freeMem = (long)value;
				} else if (TOTAL_MEM.equals(method.getName())){
					totalMem = (long)value;
				} else if (USAGE_CPU.equals(method.getName())){
					cpuUsagePerc = Float.parseFloat(String.valueOf(((double)value) * 100d));
				}
    		} // if
		} // for
    	File f1 = new File(CHECK_DISK_DIR);
    	totalDisk = f1.getTotalSpace();
    	freeDisk  = f1.getFreeSpace(); // getUsableSpace
//    	log.info("disk", "" + totalDisk + " " + freeDisk + " " + f1.getUsableSpace());
		float memUsagePerc  = (1f - freeMem * 1f / totalMem * 1f) * 100f;
		float diskUsagePerc = (1f - freeDisk * 1f / totalDisk * 1f) * 100f;
		long blockHeightUs  = 0, blockHeightInfura = 0;
		
		CryptoSharedService service = coinFactory.getService(SYMBOL_ETH);
//		log.info("cpuUsage " + cpuUsagePerc + " memUsage " + memUsagePerc 
//				+ " diskUsage " + diskUsagePerc);
		try {
			blockHeightUs     = ((OwnChain)service).getLatestblockFromChain();
			blockHeightInfura = WalletUtil.hexToLong(
					getLastBlockFromInfura().getResult());
//			log.info("lastBlockHeight " + blockHeightUs + " / " + blockHeightInfura);
		} catch (Exception e) {
			e.printStackTrace();
		}
		ret.setResult(cpuUsagePerc, memUsagePerc, diskUsagePerc
				, blockHeightUs, blockHeightInfura);
		return ret;
    }
    
    private BitcoinStringResponse getLastBlockFromInfura() throws Exception {
//    	BEST_BLOCK_HEIGHT_INFURA
    	return gson.fromJson(
    			WalletUtil.sendHttpsGet(URL_BEST_BLOCKHEIGHT_INFURA)
    			, BitcoinStringResponse.class);
    	
    }
    
    /**
     * 30분마다 통계 데이터 저장
     */
    public boolean gatherSalesData() {
    	// 시스템 상태
    	SystemStatusResponse res = getSystemStatus();
    	return true;
    }
    
}
