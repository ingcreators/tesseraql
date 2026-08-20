package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GrantViewsTest {

    private static final List<String> MEMBERS = List.of("shop-a", "shop-b");

    /** The store-wide administrator: sees every member and may write in each.  */
    private static final List<String> STORE_WIDE = List.of("tql.iam.admin.view",
            "tql.iam.admin.write");

    /** A store as maps: exact code → holder rows, plus the prefix listing. */
    private static GrantViews.ContractRunner store(Map<String, List<Map<String, Object>>> holders,
            List<Map<String, Object>> codes) {
        return (contract, params) -> switch (contract) {
            case IdentityContracts.FIND_PERMISSION_HOLDERS ->
                holders.getOrDefault(String.valueOf(params.get("code")), List.of());
            case IdentityContracts.LIST_PERMISSIONS_BY_PREFIX -> codes;
            case IdentityContracts.LIST_ROLES_BY_APPLICATION -> List.of();
            default -> throw new IllegalArgumentException(contract);
        };
    }

    private static Map<String, Object> holder(String login, String role, String path) {
        return Map.of("user_id", "u-" + login, "login_id", login, "display_name", login,
                "status", "ACTIVE", "role_code", role, "grant_type", path);
    }

    @Test
    void wildcardHoldersAreListedApartFromExactGrants() {
        GrantViews.ContractRunner runner = store(Map.of(
                "tql.app.use.shop-a", List.of(holder("usera", "r-usera", "DIRECT")),
                "tql.app.use.*", List.of(holder("admin", "r-admin", "DIRECT"))),
                List.of());
        Map<String, Object> model = GrantViews.applicationGrants("shop-a", MEMBERS, STORE_WIDE,
                runner);

        assertThat(model.get("known")).isEqualTo(1);
        assertThat(model.get("hasAny")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> families = (List<Map<String, Object>>) model.get("families");
        Map<String, Object> use = families.stream()
                .filter(f -> "use".equals(f.get("key"))).findFirst().orElseThrow();
        assertThat((List<?>) use.get("rows")).extracting("login_id").containsExactly("usera");
        assertThat((List<?>) use.get("wildcardRows")).extracting("login_id")
                .containsExactly("admin");
        assertThat(use.get("empty")).isEqualTo(0);
    }

    @Test
    void aMemberNobodyIsGrantedAnswersTheDenyByDefaultState() {
        Map<String, Object> model = GrantViews.applicationGrants("shop-b", MEMBERS,
                STORE_WIDE, store(Map.of(), List.of()));

        assertThat(model.get("hasAny")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> families = (List<Map<String, Object>>) model.get("families");
        assertThat(families).allSatisfy(family -> assertThat(family.get("empty")).isEqualTo(1));
        assertThat((List<?>) model.get("codes")).isEmpty();
    }

    @Test
    void anApplicationOutsideTheStackAnswersUnknown() {
        Map<String, Object> model = GrantViews.applicationGrants("nope", MEMBERS,
                STORE_WIDE, store(Map.of(), List.of()));
        assertThat(model.get("known")).isEqualTo(0);
        assertThat(model).doesNotContainKey("families");
    }

    @Test
    void theApplicationsOwnCodesListTheirHolders() {
        GrantViews.ContractRunner runner = store(Map.of(
                "shop-a.users.read", List.of(holder("usera", "r-usera", "DIRECT"),
                        holder("usera", "g-role", "GROUP"))),
                List.of(Map.of("permission_code", "shop-a.users.read",
                        "permission_name", "read users")));
        Map<String, Object> model = GrantViews.applicationGrants("shop-a", MEMBERS, STORE_WIDE,
                runner);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> codes = (List<Map<String, Object>>) model.get("codes");
        assertThat(codes).hasSize(1);
        assertThat(codes.get(0).get("code")).isEqualTo("shop-a.users.read");
        assertThat((List<?>) codes.get(0).get("rows")).hasSize(2);
    }

    @Test
    void aMissingContractDegradesInsteadOfFailing() {
        GrantViews.ContractRunner missing = (contract, params) -> {
            throw new TqlException(new TqlErrorCode(TqlDomain.IAM, 1001), "no such contract");
        };
        Map<String, Object> detail = GrantViews.applicationGrants("shop-a", MEMBERS, STORE_WIDE,
                missing);
        assertThat(detail.get("available")).isEqualTo(0);
        assertThat(String.valueOf(detail.get("reason"))).contains("no such contract");

        Map<String, Object> list = GrantViews.applications(MEMBERS, STORE_WIDE, missing);
        assertThat(list.get("available")).isEqualTo(0);
        assertThat((List<?>) list.get("rows")).extracting("name")
                .containsExactly("shop-a", "shop-b");
    }

    @Test
    void anyOtherFailurePropagates() {
        GrantViews.ContractRunner broken = (contract, params) -> {
            throw new TqlException(new TqlErrorCode(TqlDomain.IAM, 2000), "exec failed");
        };
        assertThatThrownBy(() -> GrantViews.applications(MEMBERS, STORE_WIDE, broken))
                .isInstanceOf(TqlException.class).hasMessageContaining("exec failed");
    }

    @Test
    void theApplicationsListCountsDistinctUsers() {
        GrantViews.ContractRunner runner = store(Map.of(
                "tql.app.use.shop-a", List.of(holder("usera", "r-a", "DIRECT"),
                        holder("usera", "r-b", "GROUP"), holder("other", "r-a", "DIRECT"))),
                List.of());
        Map<String, Object> model = GrantViews.applications(MEMBERS, STORE_WIDE, runner);

        assertThat((List<?>) model.get("rows")).extracting("holders").containsExactly(2, 0);
        assertThat(model.get("wildcardHolders")).isEqualTo(0);
    }

    @Test
    void likePatternsAreEscaped() {
        assertThat(GrantViews.escapeLike("a#b%c_d.")).isEqualTo("a##b#%c#_d.");
    }
}
