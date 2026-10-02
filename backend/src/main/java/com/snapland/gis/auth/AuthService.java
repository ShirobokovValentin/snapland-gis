package com.snapland.gis.auth;

import com.snapland.gis.auth.dto.AuthResponse;
import com.snapland.gis.auth.dto.LoginRequest;
import com.snapland.gis.auth.dto.RegisterRequest;
import com.snapland.gis.common.exception.UserAlreadyExistsException;
import com.snapland.gis.common.exception.UserNotFoundException;
import com.snapland.gis.config.PrototypeProperties;
import com.snapland.gis.user.User;
import com.snapland.gis.user.UserRepository;
import com.snapland.gis.workspace.Workspace;
import com.snapland.gis.workspace.WorkspaceMember;
import com.snapland.gis.workspace.WorkspaceMemberRepository;
import com.snapland.gis.workspace.WorkspaceMemberRole;
import com.snapland.gis.workspace.WorkspaceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final PrototypeProperties prototypeProperties;
    private final JwtIssuer jwtIssuer;

    public AuthService(UserRepository userRepository,
                       WorkspaceRepository workspaceRepository,
                       WorkspaceMemberRepository workspaceMemberRepository,
                       PrototypeProperties prototypeProperties,
                       JwtIssuer jwtIssuer) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.prototypeProperties = prototypeProperties;
        this.jwtIssuer = jwtIssuer;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String name = request.name().trim();

        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException(email);
        }

        User user;
        try {
            user = userRepository.save(new User(name, email));
        } catch (DataIntegrityViolationException ex) {
            // concurrent registration with the same email
            throw new UserAlreadyExistsException(email);
        }

        Workspace workspace = workspaceRepository.findById(prototypeProperties.workspaceId())
            .orElseThrow(() -> new IllegalStateException(
                "Prototype workspace not found: " + prototypeProperties.workspaceId()));

        workspaceMemberRepository.save(new WorkspaceMember(workspace, user, WorkspaceMemberRole.EDITOR));

        String token = jwtIssuer.issue(user.getId());
        return toResponse(token, user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UserNotFoundException(email));
        String token = jwtIssuer.issue(user.getId());
        return toResponse(token, user);
    }

    private AuthResponse toResponse(String token, User user) {
        return new AuthResponse(token, new AuthResponse.UserInfo(user.getId(), user.getName(), user.getEmail()));
    }
}
