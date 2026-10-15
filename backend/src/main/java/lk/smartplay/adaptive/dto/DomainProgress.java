package lk.smartplay.adaptive.dto;

public record DomainProgress(
        String domain,
        double averagePercentage,
        long assessmentCount,
        String progress,
        String level) {
}
