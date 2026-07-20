package sample;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class PriceService {
    public BigDecimal round(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.DOWN);
    }
}
