package br.com.olius.olius_core_api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PointsContracts {
    private PointsContracts() { }
    public record Movement(UUID deliveryId,long points,Instant deliveryDate,int revision) { }
    public record Summary(long balance,List<Movement> movements,int page,int size,long totalElements) {
        public Summary { movements=List.copyOf(movements); }
    }
}
