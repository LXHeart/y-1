package com.grassland.intelligence.credits;

import com.grassland.intelligence.security.IntelligenceException;

/** 积分不足（legacy credits 返回 402）。经 ErrorHandler 转 {@code {success:false,error:"积分不足"}}。 */
public class InsufficientCreditsException extends IntelligenceException {
    public InsufficientCreditsException() {
        super(402, "积分不足");
    }
}
