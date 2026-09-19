package adr.domain;

import java.util.UUID;

/** State is held by the Findings store, not by the record. */
public record Finding(UUID id, UUID parentFindingId, String findingType, String assetId, String subjectRole,
                      String ownerRole, String title, String description, String criticality) {
  public Finding {
    Require.nonNull(id, "id");
    Require.nonBlank(findingType, "finding_type");
    Require.nonBlank(assetId, "asset_id");
    Require.matches(subjectRole, Require.ROLE_NAME, "subject_role");
    Require.matches(ownerRole, Require.ROLE_NAME, "owner_role");
    Require.nonBlank(title, "title");
    Require.nonBlank(description, "description");
    Require.nonBlank(criticality, "criticality");
  }

  public Finding child(UUID childId) {
    return new Finding(childId, id, findingType, assetId, subjectRole, ownerRole, title, description, criticality);
  }
}
