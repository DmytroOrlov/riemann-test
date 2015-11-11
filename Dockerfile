FROM ruby

ENV DASH_HOME /root/riemann-dash
WORKDIR $DASH_HOME

RUN git clone https://github.com/aphyr/riemann-dash.git $DASH_HOME \
    && bundle \
    && echo 'set  :bind, "0.0.0.0"' > $DASH_HOME/config.rb

ENV PATH $DASH_HOME/bin:$PATH

EXPOSE 4567

CMD ["riemann-dash", "config.rb"]
