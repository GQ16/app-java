package neoflix.services;

import neoflix.AppUtils;
import neoflix.AuthUtils;
import neoflix.ValidationException;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Values;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.NoSuchRecordException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class AuthService {


    private final Driver driver;
    private final List<Map<String, Object>> users;
    private String jwtSecret;

    /**
     * The constructor expects an instance of the Neo4j Driver, which will be
     * used to interact with Neo4j.
     *
     * @param driver
     * @param jwtSecret
     */
    public AuthService(Driver driver, String jwtSecret) {
        this.driver = driver;
        this.jwtSecret = jwtSecret;
        this.users = AppUtils.loadFixtureList("users");
    }

    /**
     * This method should create a new User node in the database with the email and name
     * provided, along with an encrypted version of the password and a `userId` property
     * generated by the server.
     *
     * The properties also be used to generate a JWT `token` which should be included
     * with the returned user.
     *
     * @param email
     * @param plainPassword
     * @param name
     * @return User
     */
    // tag::register[]
    public Map<String,Object> register(String email, String plainPassword, String name) {
        var encrypted = AuthUtils.encryptPassword(plainPassword);
        // Open a new Session
        try (var session = this.driver.session()) {
            // tag::create[]
            var user = session.executeWrite(tx -> {
                String statement = """
                        CREATE (u:User {
                            userId: randomUuid(),
                            email: $email,
                            password: $encrypted,
                            name: $name
                        })
                    RETURN u { .userId, .name, .email } as u
                    """;
                var res = tx.run(statement, Values.parameters("email", email, "encrypted", encrypted, "name", name));
                // end::create[]
                // tag::extract[]
                // Extract safe properties from the user node (`u`) in the first row
                return res.single().get("u").asMap();
                // end::extract[]
            });
            String sub = (String)user.get("userId");
            String token = AuthUtils.sign(sub,userToClaims(user), jwtSecret);

            // tag::return-register[]
            return userWithToken(user, token);
            // end::return-register[]
        }
        catch (ClientException e) {
            // Handle unique constraints in the database
            if (e.code().equals("Neo.ClientError.Schema.ConstraintValidationFailed")) {
                throw new ValidationException("An account already exists with the email address", Map.of("email","Email address already taken"));
            }
            throw e;
        }
    }
    // end::register[]


    /**
     * This method should attempt to find a user by the email address provided
     * and attempt to verify the password.
     *
     * If a user is not found or the passwords do not match, a `false` value should
     * be returned.  Otherwise, the users properties should be returned along with
     * an encoded JWT token with a set of 'claims'.
     *
     * {
     *   userId: 'some-random-uuid',
     *   email: 'graphacademy@neo4j.com',
     *   name: 'GraphAcademy User',
     *   token: '...'
     * }
     *
     * @param email The user's email address
     * @param plainPassword An attempt at the user's password in unencrypted form
     * @return User    Resolves to a null value when the user is not found or password is incorrect.
     */
    // tag::authenticate[]
    public Map<String,Object> authenticate(String email, String plainPassword) {
        // Open a new Session
        try (var session = this.driver.session()) {
            // Find the User node within a Read Transaction
            var user = session.executeRead(tx -> {
                String statement = "MATCH (u:User {email: $email}) RETURN u";
                var res = tx.run(statement, Values.parameters("email", email));
                return res.single().get("u").asMap();

            });

            // Check password
            if (!AuthUtils.verifyPassword(plainPassword, (String)user.get("password"))) {
                throw new ValidationException("Incorrect password", Map.of("password","Incorrect password"));
            }

            String sub = (String)user.get("userId");
            String token = AuthUtils.sign(sub, userToClaims(user), jwtSecret);
            return userWithToken(user, token);
        } catch(NoSuchRecordException e) {
            throw new ValidationException("Incorrect email", Map.of("email","Incorrect email"));
        }
    }

    private Map<String, Object> userToClaims(Map<String,Object> user) {
        return Map.of(
                "sub", user.get("userId"),
                "userId", user.get("userId"),
                "name", user.get("name")
        );
    }
    private Map<String, Object> claimsToUser(Map<String,String> claims) {
        return Map.of(
                "userId", claims.get("sub"),
                "name", claims.get("name")
        );
    }
    private Map<String, Object> userWithToken(Map<String,Object> user, String token) {
        return Map.of(
                "token", token,
                "userId", user.get("userId"),
                "email", user.get("email"),
                "name", user.get("name")
        );
    }
}