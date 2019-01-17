package crypto.wallet.service;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.Gson;

import crypto.wallet.common.constant.WalletConst;
import crypto.wallet.common.domain.req.PersonalInfoRequest;
import crypto.wallet.common.domain.req.SendRequest;
import crypto.wallet.common.domain.res.NewAddressResponse;
import crypto.wallet.common.domain.res.SendResponse;
import crypto.wallet.common.domain.res.ValidateAddressResponse;
import crypto.wallet.common.util.WalletUtil;
import crypto.wallet.data.domain.TbAddressBalance;
import crypto.wallet.data.domain.TbCryptoMaster;
import crypto.wallet.data.domain.TbManualPayRequest;
import crypto.wallet.data.domain.TbPointExchangeRequest;
import crypto.wallet.data.domain.TbRecv;
import crypto.wallet.data.domain.TbSend;
import crypto.wallet.data.domain.TbSendRequest;
import crypto.wallet.data.domain.TbTokenBuyRequest;
import crypto.wallet.data.gson.btc.BtcRpcError;
import crypto.wallet.repo.AddressBalanceRepository;
import crypto.wallet.repo.CryptoMasterRepository;
import crypto.wallet.repo.ManualPayRequestRepository;
import crypto.wallet.repo.OrphanAddressRepository;
import crypto.wallet.repo.PointExchangeRequestRepository;
import crypto.wallet.repo.RecvRepository;
import crypto.wallet.repo.SendRepository;
import crypto.wallet.repo.SendRequestRepository;
import crypto.wallet.repo.TokenBuyRequestRepository;
import crypto.wallet.service.common.CoinFactory;
import crypto.wallet.service.common.FirebaseService;
import crypto.wallet.service.intf.OwnChain;
import crypto.wallet.service.intf.PassPhraseAddressWallet;
import crypto.wallet.service.intf.PassPhraseWallet;
import crypto.wallet.service.kafka.KafkaSender;
import lombok.extern.slf4j.Slf4j;

/**
 * 
 * @author sungjoon.kim
 */
@Slf4j public abstract class CryptoSharedService implements WalletConst {

	public final String TAG = "[" + getSymbol() + "]";
	
    @Value("${app.enabledSymbols}") protected String[] ENABLED_SYMBOLS;
    @Value("${app.enabledERC20s}")  protected String[] ENABLED_ERC20S;
    
    // wallet properties
    public abstract String getSymbol();
    public abstract String getRpcurl();
    public abstract String getReserveaddr();
    public abstract String getSendaddr();
    public abstract double getMinamtgather();
    public abstract int getDecimals();
    
    public abstract boolean isSendAddrExists();
    public abstract double getAddressBalance(String address);
    
    public abstract boolean syncWalletBalance(int uid);
    public abstract boolean syncWalletBalances();
    public abstract Object getTransaction(String txid);
    // open API functions for exchange
    public abstract NewAddressResponse newAddress(PersonalInfoRequest req);
    public abstract ValidateAddressResponse validateAddress(PersonalInfoRequest param);
    // sync tx
    public abstract void beforeBatchSend();
    public abstract boolean sendOneTransaction(TbSend datum);
    public abstract boolean updateSendConfirm();
    public abstract boolean updateReceiveConfirm();
    // gather coins to master address
    public abstract boolean sendFromHotToReserve(double amount);
    
    @Autowired private FirebaseService fsvc;
    // 입출금 테이블
    @Autowired protected AddressBalanceRepository addrBalanceRepo;
    @Autowired protected RecvRepository recvRepo;
    @Autowired protected SendRepository sendRepo;
    @Autowired protected SendRequestRepository sendReqRepo;
    @Autowired protected OrphanAddressRepository orphanAddrRepo;
    @Autowired protected CryptoMasterRepository cryptoMasterRepo;
    @Autowired protected TokenBuyRequestRepository tokenBuyRequestRepo;
    @Autowired protected ManualPayRequestRepository manualPayRequestRepository;
    @Autowired protected PointExchangeRequestRepository pointExchangeRequestRepository;
    @Autowired protected KafkaSender sender;
    @Autowired protected CoinFactory coinFactory;
    @Autowired private EntityManagerFactory emf;
    protected Gson gson = new Gson();
    
    public List<String> getAllAddressListFromDB() {
        return addrBalanceRepo.findAddrBySymbol(getSymbol());
    }
    
    @Transactional public TbCryptoMaster getTotalBalance() {
        Optional<TbCryptoMaster> ret = cryptoMasterRepo.findById(getSymbol());
        if (ret.isPresent()) {
        	return ret.get();
        }
        return null;
    }
    
    /**
     * 공통 배치 출금 
     * @return
     */
    public boolean batchSendTransaction() {
        
        // 전처리 필요 시
        beforeBatchSend();
        List<TbSend> data = getToSendList();
        
        if (data==null || data.size()<1) {
            return true;
        } else {
            
            int successcount = 0;
            int datasize = data.size();
            boolean success = false;
            
            // 1) UNLOCK NODE
            if (this instanceof PassPhraseWallet && this instanceof OwnChain) {
                success = ((PassPhraseWallet)this).walletpassphrase();
                if (!success) { return false; }
            }
            
            EntityManager em  = emf.createEntityManager();
            EntityTransaction etx = em.getTransaction();
            
            // Todo: WHAT IF SENDMANY WALLET?
            for (TbSend datum : data) {
            	etx.begin();
                // 2) SEND
                if (this instanceof PassPhraseAddressWallet) {
                    if (datum.getFromAddr()!=null && datum.getFromAddr().length()>0) {
                        success = ((PassPhraseAddressWallet)this)
                        		.walletpassphraseWithAddress(datum.getFromAddr());
                        if (!success) { 
                        	datum.setErrMsg(MSG_UNLOCK_FAIL);
                        	em.merge(datum);
                        	etx.commit();
                        	continue; 
                    	}
                    }
                }
                try {
                    success = sendOneTransaction(datum);
                    if (datum.getErrMsg()!=null && datum.getErrMsg().length()>0) {
                    	// FAIL
                        datum.setErrMsg(MSG_SEND_FAIL);
                        if (datum.getIntent()==INTENT_BUY_TOKEN) {
                        	Optional<TbTokenBuyRequest> rawdatum = tokenBuyRequestRepo.findById(datum.getOrderId());
                        	if (rawdatum.isPresent()) {
                        		TbTokenBuyRequest buydatum = rawdatum.get();
                        		buydatum.setError(datum.getErrMsg());
                        		em.merge(buydatum);
                        	}
                        	
                        }
                    }
                    if (success) { successcount++; }
                    em.merge(datum);
                    etx.commit();
                    
                    // 토큰 구매용 출금 요청인 경우
                    etx.begin();
                    if (datum.getIntent()==INTENT_BUY_TOKEN) {
                    	// 토큰 구매요청 데이터 있는지 확인
                    	Optional<TbTokenBuyRequest> rawbuydatum = 
                    			tokenBuyRequestRepo.findById(datum.getOrderId());
                    	TbTokenBuyRequest buydatum = null;
                    	if (rawbuydatum.isPresent()) {
                    		// 존재하면
                    		buydatum = rawbuydatum.get();
                    		if (getSymbol().equals(buydatum.getPaySymbol())) {
                    			// 지불을 위한 입금요청이면
                    			buydatum.setStatus(STAT_DEPOSITING);
                    		} else if (getSymbol().equals(buydatum.getTokenSymbol())) {
                    			// 지불완료 후 토큰 지급 요청이면
                    			buydatum.setStatus(STAT_WITHDRAWING);
                    		} else {
                    			buydatum.setStatus(STAT_UNKNOWN);
                    			buydatum.setError(MSG_UNCATE_CRYPTO);
                    		}
                    		em.merge(buydatum);
                            etx.commit();
                    	}
                    }
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    continue;
                }
            }
            em.close();
            data.clear();
            data = null;
            if (this instanceof PassPhraseWallet && this instanceof OwnChain) {
                // 4) LOCK NODE
                success = ((PassPhraseWallet)this).walletlock();
            }
            return (successcount==datasize);
        }
    }

    @Transactional public SendResponse requestSendTransaction(SendRequest req) {
      
        if (this instanceof BitcoinAbstractService && req.getFromAccount()==null) {
            // 2018-07-25 sungjoon.kim 비트계열 출금어카운트를 유저어카운트로 변경
            req.setFromAccount(((BitcoinAbstractService)this).getSendaccount());  
        } else if (req.getFromAddress()==null) {
            req.setFromAddress(this.getSendaddr());  
        }
        if (SYMBOL_BCD.equals(getSymbol())) {
            // sungjoon) BCD는 소수점 5자리 이하 송금 시 에러 발생하여 긴급 조치
            req.setAmount((double)Math.floor(req.getAmount()*10000d)/10000d);
        }
        SendResponse ret = new SendResponse();
        ret.setResult(req);
        
        try {
            TbSend datum = new TbSend(req);
            if (req.getIntent()==INTENT_PURETX) {
            	// 단순 출금 요청 건
            	TbSendRequest reqdatum = new TbSendRequest(req);
	            sendReqRepo.save(reqdatum);
            }
            sendRepo.save(datum);
        } catch (Exception e) {
            e.printStackTrace();
            ret.setCode(CODE_FAIL_LOGICAL);
            ret.setError(e.getMessage());
        }
        return ret;
    }
    
    /**
     * 출금내역) 
     * 1) 거래소의 경우  KAFKA 객체로 변환하여 전송
     * 2) 프라이빗 세일의 경우 구매상태 변경
     * @param send
     * @return
     */
    public boolean notifySendKafka(TbSend send) {
    	
        if (send==null) { return false; }
        if (send.getNotifiable()=='N' || send.getUid()<0) {
            // 알리지 말아야할 TX에 대한 방어코드
            send.setNotifiable('N');
            return true;
        } else {
        	// 1. 거래소의 경우 KAFKA로 알림
//            TransactionResponse item = WalletUtil.convertTbSendToResponse(datum);
//            sender.send(TopicId.walletTransmit(datum.getBrokerId()), item);
        	
        	// 2. 토큰 세일 로직: FCM으로 알림
        	if (send.getIntent()==INTENT_BUY_TOKEN) {
        		// 1) 토큰 구매의 경우 
	            Optional<TbTokenBuyRequest> rawdatum = 
	        			tokenBuyRequestRepo.findById(send.getOrderId());
	        	TbTokenBuyRequest ldatum = null;
	        	if (rawdatum.isPresent()) {
	        		// 토큰 구매요청 건이 존재하면
	        		ldatum = rawdatum.get();
	        		if (send.getErrMsg()!=null) {
	        			// 출금 에러가 있으면
	        			ldatum.setError(send.getErrMsg());
        			}
	        		if (send.getNotiCnt()<NOTI_CNT_FINISHED) {
        				ldatum.setStatus(STAT_DEPOSITING);
        			} else if (send.getNotiCnt()==NOTI_CNT_FINISHED) {
        				ldatum.setStatus(STAT_BEFOREWITHDRAWAL);
        			}
	        		tokenBuyRequestRepo.save(ldatum);
	        		if (send.getSymbol().equals(ldatum.getPaySymbol()) 
	        				&& ldatum.getStatus()==STAT_DEPOSITING) {
        				// 출금화폐가 지불절차 이면
	        			sendFcm(send.getIntent(), ldatum.getStatus(), ldatum.getPayAmount()
	        					, ldatum.getTokenAmount(), send.getFromAddr(), SYMBOL_CPD);
        			} 
	        	}
        	} else if (send.getIntent()==INTENT_POINT_EXCHANGE) {
        		// 2) 토큰 => 포인트 환전요청의 경우
	            Optional<TbPointExchangeRequest> rawdatum = 
	            		pointExchangeRequestRepository.findById(send.getOrderId());
	            TbPointExchangeRequest ldatum = null;
	        	if (rawdatum.isPresent()) {
	        		// 환전 요청 건이 존재하면
	        		ldatum = rawdatum.get();
	        		if (send.getErrMsg()!=null) {
	        			// TX 에러가 있으면
	        			ldatum.setError(send.getErrMsg());
        			}
	        		if (send.getNotiCnt()<NOTI_CNT_FINISHED) {
	        			ldatum.setStatus(STAT_PROGRESS);
	        		}
	        		pointExchangeRequestRepository.save(ldatum);
	        		if (send.getNotiCnt()<NOTI_CNT_FINISHED) {
	        			// char topicType, char status, double sendAmt, double recvAmt
	        			sendFcm(send.getIntent(), ldatum.getStatus(), ldatum.getTokenAmount()
	        					, ldatum.getPointAmount(), send.getFromAddr(), "CPP");
	        		}
	        	}
        	} else if (send.getIntent()==INTENT_MANUAL_PAY) {
        		// 바운티/수작업 토큰지급
        		Optional<TbManualPayRequest> rawdatum = 
        				manualPayRequestRepository.findById(send.getOrderId());
        		TbManualPayRequest ldatum = null;
	        	if (rawdatum.isPresent()) {
	        		// 지급 요청건이 존재하면
	        		ldatum = rawdatum.get();
	        		if (send.getErrMsg()!=null) {
	        			// TX 에러가 있으면
	        			ldatum.setError(send.getErrMsg());
        			}
	        		if (send.getNotiCnt()<NOTI_CNT_FINISHED) {
	        			ldatum.setStatus(STAT_PROGRESS);
	        		}
	        		manualPayRequestRepository.save(ldatum);
	        		if (send.getNotiCnt()<NOTI_CNT_FINISHED) {
	        			// char topicType, char status, double sendAmt, double recvAmt
	        			sendFcm(send.getIntent(), ldatum.getStatus(), 0, ldatum.getTokenAmount()
	        					, send.getToAddr(), SYMBOL_CPD);
	        		}
	        	}
        	} else if (send.getIntent()==INTENT_PURETX) {
        		char stat = STAT_PROGRESS;
        		if (send.getNotiCnt()==NOTI_CNT_FINISHED) {
        			stat = STAT_COMPLETED;
        		}
        		sendFcm(send.getIntent(), stat, send.getAmount(), 0
    					, send.getFromAddr(), send.getSymbol());
        	}
        }
        return true;
    }
    
    
    /**
     * 입금내역  KAFKA 객체로 변환하여 전송
     * @param recv
     * @return
     */
    public boolean notifyRecvKafka(TbRecv recv) {
      
        if (recv==null) { return true; }
        if (recv.getBrokerId()==null || recv.getNotifiable()=='N'
                    || BROKER_ID_SYSTEM.equals(recv.getBrokerId()) 
                    || COINBASE_NAME.equals(recv.getFromAddr())) {
            // 알리지 말아야할 TX에 대한 방어코드
            recv.setNotifiable('N');
        } else {
        	
        	// 거래소의 경우 KAFKA로 알림
//            TransactionResponse item = new TransactionResponse(datum);
//            sender.send(TopicId.walletTransmit(datum.getBrokerId()), item);
            
            // 토큰 구매의 경우 상태 업데이트
            if (recv.getIntent()==INTENT_BUY_TOKEN) {
	            Optional<TbTokenBuyRequest> rawtokendatum = 
	        			tokenBuyRequestRepo.findById(recv.getOrderId());
	        	TbTokenBuyRequest ldatum = null;
	        	if (rawtokendatum.isPresent()) {
	        		ldatum = rawtokendatum.get();
        				// 출금화폐가 토큰송금 이면
    				if (recv.getErrMsg()!=null) {
	        			// 출금 에러가 있으면
	        			ldatum.setError(recv.getErrMsg());
        			}
	        		if (recv.getNotiCnt()==NOTI_CNT_FINISHED) {
        				ldatum.setStatus(STAT_COMPLETED);
        			}
	        		tokenBuyRequestRepo.save(ldatum);
	        		if (recv.getSymbol().equals(ldatum.getTokenSymbol()) 
	        				&& ldatum.getStatus()==STAT_COMPLETED) {
        				// 출금화폐가 지불절차 이면
	        			sendFcm(recv.getIntent(), ldatum.getStatus(), ldatum.getPayAmount()
	        					, ldatum.getTokenAmount(), recv.getToAddr(), SYMBOL_CPD);
        			}  
	        	}
        	} else if (recv.getIntent()==INTENT_MANUAL_PAY) {
        		// 바운티/수작업 토큰지급
        		Optional<TbManualPayRequest> rawdatum = 
        				manualPayRequestRepository.findById(recv.getOrderId());
        		TbManualPayRequest ldatum = null;
	        	if (rawdatum.isPresent()) {
	        		// 지급 요청건이 존재하면
	        		ldatum = rawdatum.get();
	        		if (recv.getErrMsg()!=null) {
	        			// TX 에러가 있으면
	        			ldatum.setError(recv.getErrMsg());
        			}
	        		if (recv.getNotiCnt()==NOTI_CNT_FINISHED) {
	        			ldatum.setStatus(STAT_COMPLETED);
	        		}
	        		manualPayRequestRepository.save(ldatum);
	        		if (recv.getNotiCnt()==NOTI_CNT_FINISHED) {
	        			// char topicType, char status, double sendAmt, double recvAmt
	        			sendFcm(INTENT_MANUAL_PAY, ldatum.getStatus(), 0, ldatum.getTokenAmount()
	        					, recv.getToAddr(), SYMBOL_CPD);
	        		}
	        	}
        	} else if (recv.getIntent()==INTENT_PURETX) {
        		char stat = STAT_PROGRESS;
        		if (recv.getNotiCnt()==NOTI_CNT_FINISHED) {
        			stat = STAT_COMPLETED;
        		}
        		sendFcm(recv.getIntent(), stat, 0, recv.getAmount()
    					, recv.getToAddr(), recv.getSymbol());
        	}
        }
        return true;
    }
    
    
//    char STAT_UNKNOWN  	  	 = 'U'; // UNKNOWN
//    char STAT_ACCEPTED    	 = 'A'; // ACCEPTED
//    char STAT_BEFOREDEPOSIT    = 'B'; // BEFORE DEPOST
//    char STAT_DEPOSITING  	 = 'D'; // DEPOSTTING
//    char STAT_PREWITHDRAW 	 = 'P'; // BEFORE WITHDRAWAL
//    char STAT_WITHDRAWING  	 = 'W'; // WITHDRAWAL
//    char STAT_COMPLETED	  	 = 'C'; // COMPLETED
//    char STAT_FAILED   	  	 = 'F'; // FAILED
//    private boolean sendFcm(TbTokenBuyRequest send) {
//    	
//		// 1) Get Token
//    	String token = fsvc.getToken(send.getFromAddr());
//    	if (token==null) { return false; }
//    	if (!isAvaiableMessage(send.getStatus())) { return false; }
//    	
//		//2) FCM: 메시지 전송
//    	boolean success = fsvc.sendFcmMessage(token, "" + send.ㅎㄷㅅ, send.getStatus() + "|" 
//    			+ WalletUtil.toCurrencyFormat8Int(send.getTokenAmount())
//    			+ "|" + WalletUtil.toCurrencyFormat8(send.getPayAmount())
//    			+ "|" + send.getTokenSymbol()
//    					);
//		if (success) {
//			return true;
//		} else {
//			return false;
//		}
//		
//    }
    
    // 처리구분/상태값/요청수량/지급수량
    // 1) 처리구분 - T: 토큰구매(요청/지급), M: 수기/바운티지급, B:보너스지급, P: 포인트환전, S: 입출금
    // 2) 처리상태코드 - A: 접수, B: 송금전, D: 송금중, P: 지급전, W: 지급중, C: 완료, F: 실패
    // 
    private boolean sendFcm(char topicType, char status, double sendAmt, double recvAmt, String notiAddr, String currency) {
		// 1) Get FCM Token
    	String token = fsvc.getToken(notiAddr);
    	if (token==null) { return false; }
    	if (!isAvaiableMessage(status)) { return false; }
		//2) FCM: 메시지 전송
    	boolean success = fsvc.sendFcmMessage(token, "" + topicType, status + "|" 
    			+ WalletUtil.toCurrencyFormat8Int(sendAmt)
    			+ "|" + WalletUtil.toCurrencyFormat8(recvAmt)
    			+ "|" + currency
    				);
		if (success) {
			return true;
		} else {
			return false;
		}
		
    }
    
//    char STAT_UNKNOWN  	  	 = 'U'; // UNKNOWN
//    char STAT_ACCEPTED    	 = 'A'; // ACCEPTED
//    char STAT_BEFOREDEPOSIT    = 'B'; // BEFORE DEPOST
//    char STAT_DEPOSITING  	 = 'D'; // DEPOSTTING
//    char STAT_PREWITHDRAW 	 = 'P'; // BEFORE WITHDRAWAL
//    char STAT_WITHDRAWING  	 = 'W'; // WITHDRAWAL
//    char STAT_COMPLETED	  	 = 'C'; // COMPLETED
//    char STAT_FAILED   	  	 = 'F'; // FAILED
    private boolean isAvaiableMessage(char status) {
    	boolean ret = false;
    	switch (status) {
    	case STAT_DEPOSITING: case STAT_WITHDRAWING: case STAT_COMPLETED: case STAT_FAILED:
    		ret = true;
    	}
    	return ret;
    	
    }
    
    private String getMessage(char status) {
    	String ret = null;
    	switch (status) {
    	case STAT_DEPOSITING:
    		ret = " CPD 구매요청이 승인되어 처리 중 입니다";
    		break;
    	case STAT_WITHDRAWING:
    		ret = " CPD 지급 처리 중 입니다";
    		break;
    	case STAT_COMPLETED:
    		ret = " CPD 지급 완료 되었습니다";
    		break;
    	case STAT_FAILED:
    		ret = " CPD 구매처리 중 에러가 발생했습니다. 반복될 경우 관리자에게 문의해주세요.";
    		break;
    	}
    	return ret;
    	
    }
 
    /**
     * 1) 출금 처리해야할 건 조회
     * @return
     */
    public List<TbSend> getToSendList() {
        return sendRepo.findBySymbolAndTxidIsNullAndErrMsgIsNullAndRegDtGreaterThan
                (getSymbol(), getLimitDate());
    }
    /**
     * 2) 출금 진행중인 건 중에 완료처리해야 할 것 있는지 조회
     * @return
     */
    public List<TbSend> getSendTXToUpdate() {
        return getSendTXToUpdate(getSymbol());
    }
    
    /**
     * 최종 confirm 완료되지 않은 트랜잭션 목록
     * @param symbol
     * @return
     */
    public List<TbSend> getSendTXToUpdate(String symbol) {
        return sendRepo.findBySymbolAndTxidIsNotNullAndErrMsgIsNullAndNotiCntLessThanAndRegDtGreaterThan
                    (symbol, NOTI_CNT_FINISHED, getLimitDate());
    }
    
    /**
     * 입금알림) 에러가 없고 알림 갯수가 ?미만인 건
     * @return
     */
    public List<TbRecv> getRecvTXToUpdate() {
        return getRecvTXToUpdate(getSymbol());
    }
    
    public List<TbRecv> getRecvTXToUpdate(String symbol) {
        return recvRepo.findBySymbolAndErrMsgIsNullAndNotiCntLessThanAndRegDtGreaterThan
                    (symbol, NOTI_CNT_FINISHED, getLimitDate());
    }
    
    /**
     * 입출금 실패 건 중 알리지 않은 건이 있는지 조회
     * @return
     */
    @Transactional public boolean notifyTXsFailedNotNotified() {
      
        boolean success1 = true, success2 = true;
        // 1) 출금 실패 건 알림
        List<TbSend> data1 = sendRepo.findByErrMsgIsNotNullAndNotiCntLessThanAndRegDtGreaterThan
                    (NOTI_CNT_FINISHED, getLimitDate());
        if (data1!=null && data1.size()>0) {
            try {
                for (TbSend datum : data1) {
                    if (datum.getBrokerId()==null || datum.getNotifiable()=='N' 
                              || BROKER_ID_SYSTEM.equals(datum.getBrokerId())) {
                        // 출금 실패 건 중: 브로커ID가 없거나 알리지 않아야할 수신건, 시스템에서 처리하는 출금건인 경우
                        datum.setNotifiable('N');
                    } else {
                        // 출금 실패 건: 일반
//                        TransactionResponse item = WalletUtil.convertTbSendToResponse(datum);
//                        sender.send(TopicId.walletTransmit(datum.getBrokerId()), item);                           
                    }
                    datum.setNotiCnt(NOTI_CNT_FINISHED);
                }
                sendRepo.saveAll(data1);
            } catch (Exception e) {
                success1 = false;
                e.printStackTrace();
            }
        }
        
        // 2) 입금 실패 건 알림: 에러 메시지가 있고 알림 카운트가 2가 아닌 껀, 암호화폐 전체
        List<TbRecv> data2 = recvRepo.findByErrMsgIsNotNullAndNotiCntLessThanAndRegDtGreaterThan
                    (NOTI_CNT_FINISHED, getLimitDate());
        if (data2!=null && data2.size()>0) {
            try {
                for (TbRecv datum : data2) {
                    if (datum.getBrokerId()==null || datum.getNotifiable()=='N'
                                || BROKER_ID_SYSTEM.equals(datum.getBrokerId()) 
                                || COINBASE_NAME.equals(datum.getFromAddr())) {
                        // 입금 실패 건 중: 브로커ID가 없거나 알리지 않아야할 수신건, 시스템에서 처리하는 입금건인 경우
                        datum.setNotifiable('N');
                    } else {
                        // 입금 실패건: 일반
//                        TransactionResponse item = WalletUtil.convertTbRecvToResponse(datum);
//                        sender.send(TopicId.walletTransmit(datum.getBrokerId()), item);  
                    }
                    datum.setNotiCnt(NOTI_CNT_FINISHED);
                }
                recvRepo.saveAll(data2);
            } catch (Exception e) {
                success2 = false;
                e.printStackTrace();
            }
        }
        return (success1 && success2);
    }
    
    public List<TbTokenBuyRequest> getToBuyList() {
        return tokenBuyRequestRepo.findByStatusAndPaySymbolAndErrorIsNull(
        		STAT_ACCEPTED, getSymbol());
    }
    
    /**
     * insert or update
     * @return
     */
    @Transactional public boolean updateCryptoMaster() {
    	
        TbCryptoMaster master = getCryptoMaster();
        long initialblock = 0, latestblock = 0;
        try {
        	master.setDecimals(getDecimals());
        	if (isSendAddrExists()) {
            	master.setSendMastAddr(getSendaddr());
            }
        	
        	if (this instanceof OwnChain) {
	            initialblock = master.getCurrSyncHeight();
	            if (initialblock<((OwnChain)this).getInitialblock()) {
	                initialblock = ((OwnChain)this).getInitialblock();
	            }
	            if (initialblock<1) {
	            	logError("getInitialblock", "couldn't find initialblock property");
	            	return false;
	        	}
	            // get latest blocknum from chain
	            latestblock = ((OwnChain)this).getLatestblockFromChain();
	            if (latestblock>0) {
	                if (initialblock>latestblock) {
	                    logError("getLatestblockFromChain", "curr sync height is bigger than bestchain");
	                } else {
	                	master.setLatestHeight(latestblock);
	                }
	            } else {
	                master.setLatestHeight(initialblock);
	            }
        	}
            
        } catch(Exception e) {
            e.printStackTrace();
            return false;
        }
        // get latest actual fee
        TbSend datum = sendRepo.findFirstBySymbolAndRealFeeGreaterThanOrderByRegDtDesc
        		(getSymbol(), 0);
        double actualFee = 0;
        if (datum!=null && datum.getRealFee()>0) {
            actualFee = datum.getRealFee();
            master.setActualFee(actualFee); // 최근 송금 피
        }
        cryptoMasterRepo.save(master);
        return true;
    }
    
    /**
     * DB에서 마스터 테이블 조회하는 경우
     * @return
     */
    @Transactional public TbCryptoMaster getCryptoMaster() {
        Optional<TbCryptoMaster> obj = cryptoMasterRepo.findById(getSymbol());
        TbCryptoMaster master = null;
        if (obj.isPresent()) {
            master = obj.get();
        } else if (this instanceof OwnChain) {
            master = new TbCryptoMaster(getSymbol(), getSendaddr(), ((OwnChain)this).getInitialblock()
            		, ((OwnChain)this).getInitialblock());
        } else {
        	master = new TbCryptoMaster(getSymbol(), getSendaddr());
        }
        return master;
    }
    
    public NewAddressResponse getSavedAddress(PersonalInfoRequest req) {
    	NewAddressResponse ret = new NewAddressResponse();
    	ret.setResult(req);
        List<TbAddressBalance> result = addrBalanceRepo.findBySymbolAndUidAndBrokerId(getSymbol(), req.getUid(), req.getBrokerId());
        // CASE1) TABLE에 동일한 요청이 있으면 기존 생성값 리턴
        if (!result.isEmpty()) {
            if (result.size()==1) {
                logDebug("getSavedAddress", "address exists " + result);
                ret.getResult().setAddress(result.get(0).getAddr());
            } else if (result.size()>1) {
                logError("getSavedAddress", "multiple address exists " + result);
                ret.getResult().setAddress(result.get(0).getAddr());
            }
        }
        return ret;
    }
    
    /**
     * 
     * query: update TB_SEND set re_notify = 'Y' 
     * @return
     */
    @Transactional public boolean renotify() {
      
    	int renotiCnt = 0, successcnt = 0;
    	
    	try {
	        
	        List<TbSend> data1 = sendRepo.findByReNotify('Y');
	        if (data1!=null && data1.size()>0) {
	            renotiCnt += data1.size();
	            for (TbSend datum : data1) {
	                datum.setReNotify('N');
	                notifySendKafka(datum);
	                successcnt++;
	            }
	            sendRepo.saveAll(data1);
	        }
	        
	        List<TbRecv> data2 = recvRepo.findByReNotify('Y');
	        if (data2!=null && data2.size()>0) {
	            renotiCnt += data2.size();
	            for (TbRecv datum : data2) {
	                datum.setReNotify('N');
	                notifyRecvKafka(datum);
	                successcnt++;
	            }
	            recvRepo.saveAll(data2);
	        }
	        if (renotiCnt>0) {
	            logInfo("reNotify", "success/count " + successcnt + "/" + renotiCnt);
	        }
    	} catch (Exception e) { e.printStackTrace(); }
        return (successcnt==renotiCnt);
    }
    
    protected Date getLimitDate() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, SEND_UNSENT_TX_LIMIT_DATE);
        return cal.getTime();
    }
    
    protected Date getGatherLimitHour() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, SEND_GATHER_LIMIT_HOUR);
        return cal.getTime();
    }
    
    protected Date getSendRequestLimitDate() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, SEND_TX_SEARCH_LIMIT_DATE);
        return cal.getTime();
    }
    
    public void logInfo(String method, String res) {
        log.info("[" + getSymbol() + "][" + method + "] " + res);
    }
    
    public void logSuccess(String method, String res) {
        log.info("[" + getSymbol() + "][" + method + "] " + res);
    }
    
    public void logDebug(String method, String res) {
        log.debug("[" + getSymbol() + "][" + method + "] " + res);
    }
    
    public void logWarn(String method, String res) {
        log.warn("[" + getSymbol() + "][" + method + "] " + res);
    }
    
    public void logError(String method, Exception e) {
        log.error("[" + getSymbol() + "][" + method + "] " + e.getMessage());
//        e.printStackTrace();
    }
    
    public void logError(String method, BtcRpcError e) {
        log.error("[" + getSymbol() + "][" + method + "] [" + e.getCode() + "] " 
            + e.getMessage());
    }
    
    public void logError(String method, String msg) {
        log.error("[" + getSymbol() + "][" + method + "] " + msg);
    }
    
}