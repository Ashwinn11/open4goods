package org.open4goods.ui.controllers.ui;

import javax.servlet.http.HttpServletRequest;

import org.open4goods.ui.config.yml.UiConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class TeamController extends AbstractUiController {

	@Autowired
	UiConfig uiConfig;

	@GetMapping("/equipe")
	public ModelAndView index(final HttpServletRequest request) {

		ModelAndView model = defaultModelAndView("team", request);
		return model;
	}


}