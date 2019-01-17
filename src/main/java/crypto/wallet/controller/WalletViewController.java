package crypto.wallet.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import crypto.wallet.common.constant.WalletConst;
import lombok.extern.slf4j.Slf4j;


@Slf4j @Controller
public class WalletViewController implements WalletConst {


    
    @RequestMapping("/charts")
    public String dashboard() {
    	return "admin/charts";
    }
    
    @RequestMapping("/admin")
    public String admin() {
    	return "admin/index";
    }

}

