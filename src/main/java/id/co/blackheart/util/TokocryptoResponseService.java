package id.co.blackheart.util;

public class TokocryptoResponseService {
    Object code;
    Object msg;
    Object data;
    Object timestamp;

    public TokocryptoResponseService() {
    }

    public TokocryptoResponseService(Object code, Object msg, Object data,  Object timestamp) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = timestamp;
    }

    public Object getCode() {
        return this.code;
    }

    public void setCode(Object code) {
        this.code = code;
    }

    public Object getMsg() {
        return this.msg;
    }

    public void setMsg(Object msg) {
        this.msg = msg;
    }

    public Object getData() {
        return this.data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public Object getTimestamp() {
        return this.timestamp;
    }

    public void setTimestamp(Object timestamp) {
        this.timestamp = timestamp;
    }
}