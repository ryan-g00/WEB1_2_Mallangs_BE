package com.mallangs.domain.member.controller;

import com.mallangs.domain.member.dto.*;
import com.mallangs.domain.member.dto.request.LoginRequest;
import com.mallangs.domain.member.entity.Member;
import com.mallangs.domain.member.entity.embadded.UserId;
import com.mallangs.domain.member.repository.MemberRepository;
import com.mallangs.domain.member.service.MemberUserService;
import com.mallangs.global.exception.ErrorCode;
import com.mallangs.global.exception.MallangsCustomException;
import com.mallangs.global.jwt.entity.CustomMemberDetails;
import com.mallangs.global.jwt.entity.TokenCategory;
import com.mallangs.global.jwt.filter.LoginFilter;
import com.mallangs.global.jwt.service.RefreshTokenService;
import com.mallangs.global.jwt.util.JWTUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Log4j2
@RestController
@RequiredArgsConstructor
@RequestMapping("api/member")
@Tag(name = "회원", description = "회원 CRUD")
public class MemberUserController {

    private final MemberRepository memberRepository;
    // 토큰 만료 시간
    @Value("${spring.jwt.access-token-validity-in-minutes}")
    private Long accessTokenValidity;
    @Value("${spring.jwt.refresh-token-validity-in-minutes}")
    private Long accessRefreshTokenValidity;

    private final AuthenticationManager authenticationManager;
    private final MemberUserService memberUserService;
    private final RefreshTokenService refreshTokenService;
    private final JWTUtil jwtUtil;


    @PostMapping("/register")
    @Operation(summary = "회원등록", description = "회원등록 요청 API")
    public ResponseEntity<String> create(@Validated @RequestBody MemberCreateRequest memberCreateRequest) {
        return ResponseEntity.ok(memberUserService.create(memberCreateRequest));
    }

    @GetMapping("")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "회원조회", description = "회원조회 요청 API")
    public ResponseEntity<MemberGetResponse> get(Authentication authentication) {
        String userId = authentication.getName();
        return ResponseEntity.ok(memberUserService.get(userId));
    }

    @PutMapping("/{memberId}")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "회원수정", description = "회원수정 요청 API")
    public ResponseEntity<?> update(@Validated @RequestBody MemberUpdateRequest memberUpdateRequest,
                                    @PathVariable("memberId") Long memberId) {
        memberUserService.update(memberUpdateRequest, memberId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{memberId}")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "회원탈퇴", description = "회원탈퇴 요청 API")
    public ResponseEntity<?> delete(@PathVariable("memberId") Long memberId) {
        memberUserService.delete(memberId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/list")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "회원리스트 조회", description = "회원리스트 조회 요청 API")
    public ResponseEntity<Page<MemberGetResponse>> list(@RequestParam(value = "page", defaultValue = "1") int page,
                                                        @RequestParam(value = "size", defaultValue = "10") int size) {
        PageRequestDTO pageRequestDTO = PageRequestDTO.builder().page(page).size(size).build();
        return ResponseEntity.ok(memberUserService.getMemberList(pageRequestDTO));
    }

    @PostMapping("/find-user-id")
    @Operation(summary = "아이디찾기", description = "아이디찾기 요청 API")
    public ResponseEntity<String> findUserId(@Validated @RequestBody MemberFindUserIdRequest memberFindUserIdRequest) {
        return ResponseEntity.ok(memberUserService.findUserId(memberFindUserIdRequest));
    }

    @PostMapping("/find-password")
    @Operation(summary = "비밀번호찾기", description = "비밀번호찾기 요청 API")
    public ResponseEntity<String> findPassword(@Validated @RequestBody MemberFindPasswordRequest memberFindPasswordRequest) throws MessagingException {
        MemberSendMailResponse mail = memberUserService.findPassword(memberFindPasswordRequest);
        return ResponseEntity.ok(memberUserService.mailSend(mail));
    }

    @PreAuthorize("hasRole('USER')")
    @PostMapping("/check-password")
    public ResponseEntity<?> checkPassword(@Validated @RequestBody PasswordDTO passwordDTO
            , Authentication authentication) {
        String userId = authentication.getName();
        memberUserService.checkPassword(passwordDTO, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "로그인 요청 API")
    public ResponseEntity<?> login(@Validated @RequestBody LoginRequest loginRequest) {
        // 인증 토큰 생성
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginRequest.getUserId(), loginRequest.getPassword());

        // 인증 수행
        Authentication authentication = authenticationManager.authenticate(authToken);

        ///customUserDetails에서 인증정보 꺼내기
        CustomMemberDetails customMemberDetails = (CustomMemberDetails) authentication.getPrincipal();
        Long memberId = customMemberDetails.getMemberId();
        String userId = customMemberDetails.getUsername();
        String email = customMemberDetails.getEmail();
        String role = authentication.getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .orElse(null);

        // Access 토큰 생성
        Map<String, Object> AccessPayloadMap = new HashMap<>();
        AccessPayloadMap.put("memberId", memberId);
        AccessPayloadMap.put("userId", userId);
        AccessPayloadMap.put("email", email);
        AccessPayloadMap.put("role", role);
        AccessPayloadMap.put("category", TokenCategory.ACCESS_TOKEN.name());
        String accessToken = jwtUtil.createAccessToken(AccessPayloadMap, accessTokenValidity);

        //리프레시 토큰 생성 ( 난수를 입력, 의미없는 토큰 생성 )
        Map<String, Object> refreshPayloadMap = new HashMap<>();
        refreshPayloadMap.put("userId", userId);

        //식별 위한 UserID 입력
        String randomUUID = UUID.randomUUID().toString();
        refreshPayloadMap.put("randomUUID", randomUUID);
        String refreshToken = jwtUtil.createRefreshToken(refreshPayloadMap, accessRefreshTokenValidity);
        log.info("컨트롤러 로그인, 토큰만듬: {}, refresh: {}", accessToken, refreshToken);

        //리프레시 토큰 레디스에 저장하기
        refreshTokenService.insertInRedis(refreshPayloadMap, refreshToken);
        // 응답 반환
        return ResponseEntity.ok(Map.of(
                "AccessToken", accessToken,
                "RefreshToken", refreshToken
        ));
    }
}
