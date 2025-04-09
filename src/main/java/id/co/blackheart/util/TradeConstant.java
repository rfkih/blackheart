package id.co.blackheart.util;

import lombok.Getter;

public final class TradeConstant {

    @Getter
    public enum INTERVAL{
        ONE_MINUTE("1m","1 Minutes Interval"),
        FIVE_MINUTE("5m","5 Minutes Interval"),
        FIVETEEN_MINUTE("15m","15 Minutes Interval");

        private final String code;
        private final String description;
        INTERVAL(String code,String description){
            this.code=code;
            this.description=description;
        }
    }


    @Getter
    public enum JOB_TYPE{
        TRAIN_MODEL("train_model","1 Minutes Interval"),
        UPDATE_ACCOUNT_BALANCE("update_balance","5 Minutes Interval");

        private final String code;
        private final String description;
        JOB_TYPE(String code,String description){
            this.code=code;
            this.description=description;
        }
    }

}
