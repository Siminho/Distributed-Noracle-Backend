package i5.las2peer.services.noracleService.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;

public class VotedQuestion extends Question implements Serializable {

	@Serial
	private static final long serialVersionUID = -7969518021993650216L;

	private VoteList votes;
	
	public VotedQuestion() { // used in tests
		
	}

	public VotedQuestion(Question q) {
		this.setQuestionId(q.getQuestionId());
		this.setText(q.getText());
		this.setSpaceId(q.getSpaceId());
		this.setAuthorId(q.getAuthorId());
		this.setTimestampCreated(q.getTimestampCreated());
		this.setTimestampLastModified(q.getTimestampLastModified());
		this.setDepth(q.getDepth());
		if (q instanceof VotedQuestion) {
			this.setVotes(((VotedQuestion) q).getVotes());
		}
	}
	
	public VoteList getVotes() {
		return votes;
	}

	public void setVotes(VoteList votes) {
		this.votes = votes;
	}

}
