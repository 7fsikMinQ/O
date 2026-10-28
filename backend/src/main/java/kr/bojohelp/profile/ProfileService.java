package kr.bojohelp.profile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 출연자 프로필(API-ME02/ME03). 주민번호가 등록된 뒤에는 생년월일·성별 변경을 막는다(주민번호와 일치해야 하므로). */
@Service
public class ProfileService {

    public record ProfileRequest(
            @NotNull @Past LocalDate birthDate,
            @NotBlank @Pattern(regexp = "MALE|FEMALE|OTHER") String gender,
            @NotNull @Min(50) @Max(250) Integer heightCm,
            @NotNull @Min(20) @Max(300) Integer weightKg,
            @Size(max = 10) String topSize,
            @Size(max = 10) String bottomSize,
            @Min(150) @Max(350) Integer shoeSize,
            @Pattern(regexp = "SHAVED|SHORT|MEDIUM|LONG") String hairLength,
            @Size(max = 100) String hairStyle,
            Boolean hasTattoo,
            Boolean canDrive,
            Boolean hasVehicle,
            Long regionId,
            @Size(max = 10) String homePostalCode,
            @Size(max = 200) String homeAddress,
            @Size(max = 200) String homeAddressDetail,
            @Size(max = 200) String defaultDepartureLocation,
            @Size(max = 1000) String introduction,
            @Size(max = 1000) String experienceNote,
            Boolean isSearchable) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public ProfileService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean exists(long userId) {
        return jdbc.sql("SELECT count(*) FROM actor_profiles WHERE user_id = :u").param("u", userId).query(Integer.class).single() > 0;
    }

    public void save(long userId, ProfileRequest r) {
        if (r.birthDate().getYear() < 1900) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);

        Optional<Object[]> current = jdbc.sql("SELECT birth_date, gender FROM actor_profiles WHERE user_id = :u").param("u", userId)
                .query((rs, n) -> new Object[] {rs.getObject("birth_date", LocalDate.class), rs.getString("gender")}).optional();
        if (current.isPresent()) {
            boolean changed = !current.get()[0].equals(r.birthDate()) || !current.get()[1].equals(r.gender());
            boolean hasRrn = jdbc.sql("SELECT count(*) FROM actor_tax_identities WHERE user_id = :u AND is_active").param("u", userId).query(Integer.class).single() > 0;
            if (changed && hasRrn) throw new ResponseStatusException(HttpStatus.CONFLICT);   // 주민번호 등록 후 생년월일·성별 변경 불가(고객센터 처리)
        }
        try {
            jdbc.sql("""
                    INSERT INTO actor_profiles (user_id, birth_date, gender, height_cm, weight_kg, top_size, bottom_size, shoe_size, hair_length, hair_style,
                        has_tattoo, can_drive, has_vehicle, region_id, home_postal_code, home_address, home_address_detail, default_departure_location,
                        introduction, experience_note, is_searchable)
                    VALUES (:u, :birth, :gender, :h, :w, :top, :bottom, :shoe, :hairLen, :hairStyle,
                        COALESCE(:tattoo, FALSE), COALESCE(:drive, FALSE), COALESCE(:vehicle, FALSE), :region, :zip, :addr, :addr2, :dep,
                        :intro, :exp, COALESCE(:searchable, TRUE))
                    ON CONFLICT (user_id) DO UPDATE SET
                        birth_date = EXCLUDED.birth_date, gender = EXCLUDED.gender, height_cm = EXCLUDED.height_cm, weight_kg = EXCLUDED.weight_kg,
                        top_size = EXCLUDED.top_size, bottom_size = EXCLUDED.bottom_size, shoe_size = EXCLUDED.shoe_size,
                        hair_length = EXCLUDED.hair_length, hair_style = EXCLUDED.hair_style, has_tattoo = EXCLUDED.has_tattoo,
                        can_drive = EXCLUDED.can_drive, has_vehicle = EXCLUDED.has_vehicle, region_id = EXCLUDED.region_id,
                        home_postal_code = EXCLUDED.home_postal_code, home_address = EXCLUDED.home_address,
                        home_address_detail = EXCLUDED.home_address_detail,
                        default_departure_location = COALESCE(EXCLUDED.default_departure_location, actor_profiles.default_departure_location),
                        introduction = EXCLUDED.introduction, experience_note = EXCLUDED.experience_note,
                        is_searchable = EXCLUDED.is_searchable, updated_at = now()
                    """)
                    .param("u", userId).param("birth", r.birthDate()).param("gender", r.gender()).param("h", r.heightCm()).param("w", r.weightKg())
                    .param("top", r.topSize()).param("bottom", r.bottomSize()).param("shoe", r.shoeSize()).param("hairLen", r.hairLength())
                    .param("hairStyle", r.hairStyle()).param("tattoo", r.hasTattoo()).param("drive", r.canDrive()).param("vehicle", r.hasVehicle())
                    .param("region", r.regionId()).param("zip", r.homePostalCode()).param("addr", r.homeAddress()).param("addr2", r.homeAddressDetail())
                    .param("dep", r.defaultDepartureLocation()).param("intro", r.introduction()).param("exp", r.experienceNote())
                    .param("searchable", r.isSearchable())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);   // 존재하지 않는 지역 등
        }
    }

    public Map<String, Object> get(long userId) {
        Map<String, Object> m = jdbc.sql("SELECT * FROM actor_profiles WHERE user_id = :u").param("u", userId)
                .query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    LocalDate birth = rs.getObject("birth_date", LocalDate.class);
                    o.put("birthDate", birth.toString());
                    o.put("gender", rs.getString("gender"));
                    o.put("heightCm", rs.getInt("height_cm"));
                    o.put("weightKg", rs.getInt("weight_kg"));
                    o.put("topSize", rs.getString("top_size"));
                    o.put("bottomSize", rs.getString("bottom_size"));
                    o.put("shoeSize", rs.getObject("shoe_size"));
                    o.put("hairLength", rs.getString("hair_length"));
                    o.put("hairStyle", rs.getString("hair_style"));
                    o.put("hasTattoo", rs.getBoolean("has_tattoo"));
                    o.put("canDrive", rs.getBoolean("can_drive"));
                    o.put("hasVehicle", rs.getBoolean("has_vehicle"));
                    o.put("regionId", rs.getObject("region_id"));
                    o.put("homePostalCode", rs.getString("home_postal_code"));
                    o.put("homeAddress", rs.getString("home_address"));
                    o.put("homeAddressDetail", rs.getString("home_address_detail"));
                    o.put("defaultDepartureLocation", rs.getString("default_departure_location"));
                    o.put("introduction", rs.getString("introduction"));
                    o.put("experienceNote", rs.getString("experience_note"));
                    o.put("isSearchable", rs.getBoolean("is_searchable"));
                    int age = Period.between(birth, LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))).getYears();
                    o.put("age", age);
                    o.put("isAdult", age >= 19);   // 만 19세 미만은 지원 불가(동의서 구조만 선반영)
                    o.put("confirmedCount", rs.getInt("confirmed_count"));
                    o.put("noshowCount", rs.getInt("noshow_count"));
                    return o;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        m.put("hasPayoutAccount", jdbc.sql("SELECT count(*) FROM actor_payout_accounts WHERE user_id = :u").param("u", userId).query(Integer.class).single() > 0);
        m.put("hasTaxIdentity", jdbc.sql("SELECT count(*) FROM actor_tax_identities WHERE user_id = :u AND is_active").param("u", userId).query(Integer.class).single() > 0);
        return m;
    }
}
