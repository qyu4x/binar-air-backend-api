package com.binarair.binarairrestapi.model.request;

import lombok.*;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder

public class BagageRequest {
    @NotEmpty(message = "aircraft id is required")
    private String aircraftId;

    @DecimalMax(value = "9999999999.999", message = "The decimal value can not be more than 9999999999.999")
    private BigDecimal bagagePricePer5kg;

    @NotNull(message = "free bagage capacity is required")
    @Min(value = 0)
    private Integer freeBagageCapacity;

    @NotNull(message = "free cabin capacity is required")
    private Integer freeCabinCapacity;
}
