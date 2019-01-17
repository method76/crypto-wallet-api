package crypto.wallet.service;

import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Query;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import crypto.wallet.common.constant.WalletConst;
import crypto.wallet.data.domain.TbCryptoMaster;
import crypto.wallet.data.gson.common.TotalBalance;
import crypto.wallet.repo.CryptoMasterRepository;
import crypto.wallet.repo.FiatDepositRepository;
import crypto.wallet.repo.TokenBuyRequestRepository;
import lombok.extern.slf4j.Slf4j;


/**
 * KRW services
 * @author sungjoon.kim
 */
@Slf4j @Service("krwRpcService") 
public class KRWRpcService implements WalletConst {
	
	@Autowired FiatDepositRepository fiatDepositRepo;
	@Autowired CryptoMasterRepository cryptoMasterRepo;
	@Autowired TokenBuyRequestRepository tokenBuyRequestRepo;
	@Autowired private EntityManagerFactory emf;
	private final String KRW_SUM_STR 
			= "select 'KRW' as symbol, a.total, b.owners, (a.total - b.owners) as users, b.owners as avail"
			+ " from ( select ifnull(sum(amount),0) as total from tb_fiat_deposit where symbol = 'KRW' ) a," 
			+ " ( select ifnull(sum(pay_amount),0) as owners from tb_token_buy_request where pay_symbol = 'KRW' ) b";
	
	public String getSymbol() { return SYMBOL_KRW; }

	public double getAddressBalance(int uid) {
		return 0;
	}

	public boolean syncWalletBalance(int uid) {
		return true;
	}

	/**
	 * KRW 잔고 Sync
	 * @return
	 */
	public boolean syncWalletBalances() {
		
		EntityManager em = emf.createEntityManager();
		Query q = em.createNativeQuery(KRW_SUM_STR, TotalBalance.class);
		TotalBalance result = (TotalBalance)q.getSingleResult();
		Optional<TbCryptoMaster> rawdatum = cryptoMasterRepo.findById(SYMBOL_KRW);
		if (rawdatum.isPresent()) {
			// TbCryptoMaster에 저장 - total, owners, users, avail
            EntityTransaction etx = em.getTransaction();
            try {
	            etx.begin();
				TbCryptoMaster datum = rawdatum.get();
				datum.setTotalBal(result.getTotal());
				datum.setSendMastBal(result.getAvail());
				datum.setTheOtherBal(result.getUsers());
				em.merge(datum);
				etx.commit();
            } catch (Exception e) {
            	e.printStackTrace();
            	etx.rollback();
            }
		}
		em.close();
		return false;
	}
    
//	@Transactional public int[] sendCryptoToBuyToken() {
//    	int[] ret = {0, 0};
//    	List<Character> statuses = new ArrayList<>();
//    	statuses.add(STAT_ACCEPTED);
//    	List<TbTokenBuyRequest> data = tokenBuyRequestRepo.
//    			findByPaySymbolAndStatusIn(getSymbol(),  statuses);
//    	if (data==null || data.size()<1) { return ret; }
//    	
//    	ret[1] = data.size();
//    	for (TbTokenBuyRequest datum : data) {
//    		// 토큰 구매 요청
//	    	SendRequest req = new SendRequest(datum.getTokenSymbol(), 
//	    			datum.getOrderId(), datum.getUid(), datum.getFromAddr(), 
//	    			null, datum.getTokenAmount(), BROKER_ID_SYSTEM, 0);
//	    	req.setIntent(INTENT_BUYTOKEN);
//	    	req.setNotifiable('Y');
//	        SendResponse res = requestSendTransaction(req);
//	        if (res!=null && res.getCode()==CODE_SUCCESS) {
//	        	datum.setStatus(STAT_WITHDRAWING);
//	        	ret[1]++;
//	        } else {
//	        	datum.setStatus(STAT_PRE_WITHRAW);
//	        	datum.setError(res.getError());
//	        }
//	    }
//    	tokenBuyRequestRepo.saveAll(data);
//    	return ret;
//    }
	
}
