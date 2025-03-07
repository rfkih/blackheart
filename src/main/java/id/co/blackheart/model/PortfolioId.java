package id.co.blackheart.model;

import java.io.Serializable;
import java.util.Objects;

public class PortfolioId implements Serializable {
    private Long userId;
    private String asset;


    public PortfolioId() {}

    public PortfolioId(Long userId, String asset) {
        this.userId = userId;
        this.asset = asset;
    }


    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PortfolioId that = (PortfolioId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(asset, that.asset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, asset);
    }
}
