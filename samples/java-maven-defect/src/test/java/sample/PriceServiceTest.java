package sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceServiceTest {
    @Test
    void roundsMoneyHalfUp() {
        assertThat(new PriceService().round(new BigDecimal("1.235")))
            .isEqualByComparingTo("1.24");
    }
}
