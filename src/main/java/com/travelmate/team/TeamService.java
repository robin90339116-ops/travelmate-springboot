package com.travelmate.team;

import com.travelmate.common.ApiException;
import com.travelmate.domain.Team;
import com.travelmate.domain.TeamMember;
import com.travelmate.domain.User;
import com.travelmate.repository.TeamMemberRepository;
import com.travelmate.repository.TeamRepository;
import com.travelmate.repository.UserRepository;
import com.travelmate.team.TeamDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TeamService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TeamRepository teamRepository;
    private final TeamMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final TeamBroadcaster broadcaster;
    private final com.travelmate.repository.SpotRepository spots;

    @Transactional
    public TeamView createTeam(Long userId, CreateTeamRequest request) {
        Team team = new Team();
        team.setTeamCode(generateCode());
        team.setOwnerId(userId);
        team.setRouteId(request == null ? null : request.routeId());
        teamRepository.save(team);
        addMember(team.getId(), userId, "owner");
        return toView(team);
    }

    @Transactional
    public TeamView joinTeam(Long userId, JoinTeamRequest request) {
        if (request == null || request.teamCode() == null || request.teamCode().isBlank()) {
            throw ApiException.badRequest("请输入房间邀请码");
        }
        Team team = teamRepository.findByTeamCode(request.teamCode().trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("房间不存在或邀请码错误"));
        if (memberRepository.findByTeamIdAndUserId(team.getId(), userId).isEmpty()) {
            TeamMember member = addMember(team.getId(), userId, "member");
            if (request.memberName() != null && !request.memberName().isBlank()) {
                member.setMemberName(request.memberName());
                memberRepository.save(member);
            }
        }
        TeamView view = toView(team);
        broadcaster.broadcast(team.getId(), "members:update", view.members());
        return view;
    }

    public TeamView getTeam(Long teamId) {
        requireMember(teamId, com.travelmate.common.CurrentUser.id());
        Team team = requireTeam(teamId);
        return toView(team);
    }

    @Transactional
    public void leave(Long userId, Long teamId) {
        Team team = requireTeam(teamId);
        requireMember(teamId, userId);
        if (team.getOwnerId().equals(userId)) {
            var others = memberRepository.findByTeamIdOrderByJoinedAtAsc(teamId).stream()
                    .filter(m -> !m.getUserId().equals(userId)).toList();
            if (others.isEmpty()) {
                memberRepository.findByTeamIdAndUserId(teamId, userId).ifPresent(memberRepository::delete);
                teamRepository.delete(team);
                return;
            }
            var next = others.get(0);
            next.setRole("owner");
            team.setOwnerId(next.getUserId());
            memberRepository.save(next);
            teamRepository.save(team);
        }
        memberRepository.findByTeamIdAndUserId(teamId, userId)
                .ifPresent(memberRepository::delete);
        broadcaster.broadcast(team.getId(), "members:update", toView(team).members());
    }

    @Transactional
    public void kick(Long ownerId, Long teamId, Long targetUserId) {
        Team team = requireOwner(ownerId, teamId);
        if (targetUserId.equals(ownerId)) {
            throw ApiException.badRequest("队长不能踢出自己");
        }
        memberRepository.findByTeamIdAndUserId(teamId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("成员不存在"));
        memberRepository.findByTeamIdAndUserId(teamId, targetUserId).ifPresent(memberRepository::delete);
        broadcaster.broadcast(team.getId(), "member:kicked", targetUserId);
        broadcaster.broadcast(team.getId(), "members:update", toView(team).members());
    }

    @Transactional
    public void transferOwner(Long ownerId, Long teamId, Long targetUserId) {
        Team team = requireOwner(ownerId, teamId);
        TeamMember target = memberRepository.findByTeamIdAndUserId(teamId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("目标成员不存在"));
        TeamMember owner = memberRepository.findByTeamIdAndUserId(teamId, ownerId)
                .orElseThrow(() -> ApiException.notFound("队长记录缺失"));
        owner.setRole("member");
        target.setRole("owner");
        team.setOwnerId(targetUserId);
        memberRepository.save(owner);
        memberRepository.save(target);
        teamRepository.save(team);
        broadcaster.broadcast(team.getId(), "members:update", toView(team).members());
    }

    @Transactional
    public TeamView updatePlayback(Long userId, Long teamId, PlaybackRequest request) {
        if (request == null || (request.playbackStatus() != null &&
                !java.util.Set.of("playing", "paused", "stopped").contains(request.playbackStatus())))
            throw ApiException.badRequest("播放状态必须是playing、paused或stopped");
        Team team = requireTeam(teamId);
        requireMember(teamId, userId);
        if (request.currentPointId() != null) {
            try {
                if(!spots.existsById(Long.parseLong(request.currentPointId())))throw ApiException.notFound("共享地点不存在");
            } catch(NumberFormatException e){throw ApiException.badRequest("共享地点编号无效");}
            team.setCurrentPointId(request.currentPointId());
        }
        if (request.playbackStatus() != null) {
            team.setPlaybackStatus(request.playbackStatus());
        }
        teamRepository.save(team);
        broadcaster.broadcast(team.getId(), "playback:update",
                java.util.Map.of("currentPointId", nz(team.getCurrentPointId()),
                        "playbackStatus", nz(team.getPlaybackStatus())));
        return toView(team);
    }

    private TeamMember addMember(Long teamId, Long userId, String role) {
        TeamMember member = new TeamMember();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setRole(role);
        member.setMemberName(userRepository.findById(userId).map(User::getDisplayName).orElse("旅行者"));
        return memberRepository.save(member);
    }

    private Team requireTeam(Long teamId) {
        return teamRepository.findById(teamId).orElseThrow(() -> ApiException.notFound("房间不存在"));
    }

    private Team requireOwner(Long ownerId, Long teamId) {
        Team team = requireTeam(teamId);
        if (!team.getOwnerId().equals(ownerId)) {
            throw ApiException.forbidden("仅队长可执行该操作");
        }
        return team;
    }

    public void requireMember(Long teamId, Long userId) {
        memberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> ApiException.forbidden("你不在该房间内"));
    }

    private TeamView toView(Team team) {
        List<MemberView> members = memberRepository.findByTeamIdOrderByJoinedAtAsc(team.getId()).stream()
                .map(m -> new MemberView(m.getUserId(), m.getMemberName(), m.getRole()))
                .toList();
        return new TeamView(team.getId(), team.getTeamCode(), team.getOwnerId(), team.getRouteId(),
                team.getCurrentPointId(), team.getPlaybackStatus(), members);
    }

    private String generateCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (teamRepository.findByTeamCode(code).isEmpty()) {
                return code;
            }
        }
        throw ApiException.serviceUnavailable("生成房间邀请码失败,请重试");
    }

    private String nz(String v) {
        return v == null ? "" : v;
    }
}
