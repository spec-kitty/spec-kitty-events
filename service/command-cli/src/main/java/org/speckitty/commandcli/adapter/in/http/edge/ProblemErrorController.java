package org.speckitty.commandcli.adapter.in.http.edge;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The container's error page, copied from the read service: an error the container routes to {@code /error} (an unknown path, a method it
 * refuses) is answered with the shared Problem (its status and reason phrase, no code, nothing taken from the request) instead of Boot's
 * default body, which echoes the request path and a timestamp. A request that names {@code /error} itself is a 404.
 */
@RestController
class ProblemErrorController implements ErrorController {

  record StatusProblem(int status, String title) {}

  @RequestMapping("${server.error.path:/error}")
  ResponseEntity<StatusProblem> error(HttpServletRequest request) {
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
    if (code == null) {
      status = HttpStatus.NOT_FOUND;
    } else if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(new StatusProblem(status.value(), status.getReasonPhrase()));
  }
}
